load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "internal",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.internal.CatfishInternalTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/internal",
        "//java/de/ofahrt/catfish/model",
    ],
)
