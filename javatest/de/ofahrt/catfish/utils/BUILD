load("@rules_java//java:defs.bzl", "java_library", "java_test")

java_library(
    name = "test_helper",
    testonly = True,
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = ["HttpContentTypeTest.java"],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//java/de/ofahrt/catfish/utils",
        "//third_party/junit",
    ],
)

java_test(
    name = "utils",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(
        ["*.java"],
        exclude = ["HttpContentTypeTest.java"],
    ),
    test_class = "de.ofahrt.catfish.utils.CatfishUtilsTestSuite",
    deps = [
        ":test_helper",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/utils",
    ],
)
