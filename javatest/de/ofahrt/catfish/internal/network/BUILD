load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "network",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.internal.network.NetworkEngineTest",
    deps = [
        "//java/de/ofahrt/catfish/internal/network",
        "//java/de/ofahrt/catfish/model/network",
    ],
)
