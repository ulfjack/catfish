load("@rules_java//java:defs.bzl", "java_library", "java_test")

java_library(
    name = "test_helper",
    testonly = True,
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = ["AbstractPreconditionsTest.java"],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//third_party/junit",
    ],
)

java_test(
    name = "model",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(
        ["*.java"],
        exclude = ["AbstractPreconditionsTest.java"],
    ),
    test_class = "de.ofahrt.catfish.model.ModelTestSuite",
    deps = [
        ":test_helper",
        "//java/de/ofahrt/catfish/model",
    ],
)
