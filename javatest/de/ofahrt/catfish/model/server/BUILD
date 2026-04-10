load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "server",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.model.server.ModelServerTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/server",
    ],
)
