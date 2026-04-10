load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "layout",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.model.layout.ModelLayoutTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/layout",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//javatest/de/ofahrt/catfish/model:test_helper",
    ],
)
