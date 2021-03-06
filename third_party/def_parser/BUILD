licenses(["notice"])  # 3-clause BSD

cc_library(
    name = "def_parser_lib",
    srcs = ["def_parser.cc"],
    hdrs = ["def_parser.h"],
)

cc_binary(
    name = "def_parser",
    srcs = ["def_parser_main.cc"],
    visibility = [
        "//src:__pkg__",
    ],
    deps = [
        ":def_parser_lib",
    ],
)

py_test(
    name = "def_parser_test",
    srcs = select({
        "//src/conditions:windows": ["def_parser_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    main = select({
        "//src/conditions:windows": "def_parser_test.py",
        "//conditions:default": "empty_test.py",
    }),
    data = select({
        "//src/conditions:windows": [":def_parser"],
        "//conditions:default": [],
    }),
    deps = ["//src/test/py/bazel:test_base"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = [
        "//src:__pkg__",
        "//third_party:__pkg__",
    ],
)

test_suite(
    name = "windows_tests",
    tags = [
        "-no_windows",
        "-slow",
    ],
    visibility = ["//visibility:private"],
)

test_suite(
    name = "all_windows_tests",
    tests = [
        ":windows_tests",
    ],
    visibility = ["//third_party:__pkg__"],
)
