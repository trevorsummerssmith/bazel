// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.android;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.BazelMockAndroidSupport;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AndroidSkylarkTest extends BuildViewTestCase {

  private void writeAndroidSplitTransitionTestFiles() throws Exception  {
    scratch.file(
        "test/skylark/my_rule.bzl",
        "def impl(ctx): ",
        "  return struct(",
        "    split_attr_deps = ctx.split_attr.deps,",
        "    split_attr_dep = ctx.split_attr.dep,",
        "    k8_deps = ctx.split_attr.deps.get('k8', None),",
        "    attr_deps = ctx.attr.deps,",
        "    attr_dep = ctx.attr.dep)",
        "my_rule = rule(",
        "  implementation = impl,",
        "  attrs = {",
        "    'deps': attr.label_list(cfg = android_common.multi_cpu_configuration),",
        "    'dep':  attr.label(cfg = android_common.multi_cpu_configuration),",
        "  })");

    scratch.file(
        "test/skylark/BUILD",
        "load('//test/skylark:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'test', deps = [':main1', ':main2'], dep = ':main1')",
        "cc_binary(name = 'main1', srcs = ['main1.c'])",
        "cc_binary(name = 'main2', srcs = ['main2.c'])");
  }

  @Test
  public void testAndroidSplitTransition() throws Exception {
    writeAndroidSplitTransitionTestFiles();

    useConfiguration("--fat_apk_cpu=k8,armeabi-v7a");
    ConfiguredTarget target = getConfiguredTarget("//test/skylark:test");

    // Check that ctx.split_attr.deps has this structure:
    // {
    //   "k8": [ConfiguredTarget],
    //   "armeabi-v7a": [ConfiguredTarget],
    // }
    @SuppressWarnings("unchecked")
    Map<String, List<ConfiguredTarget>> splitDeps =
        (Map<String, List<ConfiguredTarget>>) target.get("split_attr_deps");
    assertThat(splitDeps).containsKey("k8");
    assertThat(splitDeps).containsKey("armeabi-v7a");
    assertThat(splitDeps.get("k8")).hasSize(2);
    assertThat(splitDeps.get("armeabi-v7a")).hasSize(2);
    assertThat(splitDeps.get("k8").get(0).getConfiguration().getCpu()).isEqualTo("k8");
    assertThat(splitDeps.get("k8").get(1).getConfiguration().getCpu()).isEqualTo("k8");
    assertThat(splitDeps.get("armeabi-v7a").get(0).getConfiguration().getCpu())
        .isEqualTo("armeabi-v7a");
    assertThat(splitDeps.get("armeabi-v7a").get(1).getConfiguration().getCpu())
        .isEqualTo("armeabi-v7a");

    // Check that ctx.split_attr.dep has this structure (that is, that the values are not lists):
    // {
    //   "k8": ConfiguredTarget,
    //   "armeabi-v7a": ConfiguredTarget,
    // }
    @SuppressWarnings("unchecked")
    Map<String, ConfiguredTarget> splitDep =
        (Map<String, ConfiguredTarget>) target.get("split_attr_dep");
    assertThat(splitDep).containsKey("k8");
    assertThat(splitDep).containsKey("armeabi-v7a");
    assertThat(splitDep.get("k8").getConfiguration().getCpu()).isEqualTo("k8");
    assertThat(splitDep.get("armeabi-v7a").getConfiguration().getCpu()).isEqualTo("armeabi-v7a");

    // The regular ctx.attr.deps should be a single list with all the branches of the split merged
    // together (i.e. for aspects).
    @SuppressWarnings("unchecked")
    List<ConfiguredTarget> attrDeps = (List<ConfiguredTarget>) target.get("attr_deps");
    assertThat(attrDeps).hasSize(4);
    ListMultimap<String, Object> attrDepsMap = ArrayListMultimap.create();
    for (ConfiguredTarget ct : attrDeps) {
      attrDepsMap.put(ct.getConfiguration().getCpu(), target);
    }
    assertThat(attrDepsMap).valuesForKey("k8").hasSize(2);
    assertThat(attrDepsMap).valuesForKey("armeabi-v7a").hasSize(2);

    // Check that even though my_rule.dep is defined as a single label, ctx.attr.dep is still a list
    // with multiple ConfiguredTarget objects because of the two different CPUs.
    @SuppressWarnings("unchecked")
    List<ConfiguredTarget> attrDep = (List<ConfiguredTarget>) target.get("attr_dep");
    assertThat(attrDep).hasSize(2);
    ListMultimap<String, Object> attrDepMap = ArrayListMultimap.create();
    for (ConfiguredTarget ct : attrDep) {
      attrDepMap.put(ct.getConfiguration().getCpu(), target);
    }
    assertThat(attrDepMap).valuesForKey("k8").hasSize(1);
    assertThat(attrDepMap).valuesForKey("armeabi-v7a").hasSize(1);

    // Check that the deps were correctly accessed from within Skylark.
    @SuppressWarnings("unchecked")
    List<ConfiguredTarget> k8Deps = (List<ConfiguredTarget>) target.get("k8_deps");
    assertThat(k8Deps).hasSize(2);
    assertThat(k8Deps.get(0).getConfiguration().getCpu()).isEqualTo("k8");
    assertThat(k8Deps.get(1).getConfiguration().getCpu()).isEqualTo("k8");
  }

  @Test
  public void testAndroidSplitTransitionWithAndroidCpu() throws Exception {
    writeAndroidSplitTransitionTestFiles();
    BazelMockAndroidSupport.setupNdk(mockToolsConfig);

    // --android_cpu with --android_crosstool_top also triggers the split transition.
    useConfiguration(
        "--android_cpu=armeabi-v7a", "--android_crosstool_top=//android/crosstool:everything");
    ConfiguredTarget target = getConfiguredTarget("//test/skylark:test");

    @SuppressWarnings("unchecked")
    Map<Object, List<ConfiguredTarget>> splitDeps =
        (Map<Object, List<ConfiguredTarget>>) target.get("split_attr_deps");

    String cpu = "armeabi-v7a";
    assertThat(splitDeps.get(cpu)).hasSize(2);
    assertThat(splitDeps.get(cpu).get(0).getConfiguration().getCpu()).isEqualTo(cpu);
    assertThat(splitDeps.get(cpu).get(1).getConfiguration().getCpu()).isEqualTo(cpu);
  }

  @Test
  public void testAndroidSplitTransitionNoTransition() throws Exception {
    writeAndroidSplitTransitionTestFiles();

    useConfiguration("--fat_apk_cpu=", "--android_crosstool_top=", "--cpu=k8");
    ConfiguredTarget target = getConfiguredTarget("//test/skylark:test");

    @SuppressWarnings("unchecked")
    Map<Object, List<ConfiguredTarget>> splitDeps =
        (Map<Object, List<ConfiguredTarget>>) target.get("split_attr_deps");

    // Split transition isn't in effect, so the deps are compiled normally (i.e. using --cpu).
    assertThat(splitDeps.get(Runtime.NONE)).hasSize(2);
    assertThat(splitDeps.get(Runtime.NONE).get(0).getConfiguration().getCpu()).isEqualTo("k8");
    assertThat(splitDeps.get(Runtime.NONE).get(1).getConfiguration().getCpu()).isEqualTo("k8");
  }

  @Test
  public void testAndroidSdkConfigurationField() throws Exception {
    scratch.file(
        "foo_library.bzl",
        "def _impl(ctx):",
        "  return struct(foo = ctx.attr._android_sdk.label)",
        "foo_library = rule(implementation = _impl,",
        "    attrs = { '_android_sdk': attr.label(default = configuration_field(",
        "        fragment = 'android', name = 'android_sdk_label'))},",
        "    fragments = ['android'])");
    scratch.file(
        "BUILD",
        "load('//:foo_library.bzl', 'foo_library')",
        "filegroup(name = 'new_sdk')",
        "foo_library(name = 'lib')");
    useConfiguration("--android_sdk=//:new_sdk");
    ConfiguredTarget ct = getConfiguredTarget("//:lib");
    assertThat(ct.get("foo")).isEqualTo(Label.parseAbsoluteUnchecked("//:new_sdk"));
  }
}
