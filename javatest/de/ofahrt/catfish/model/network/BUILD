load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "network",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.model.network.ModelNetworkTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model/network",
    ],
)
