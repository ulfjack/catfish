load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "legacy",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.client.legacy.LegacyIncrementalHttpResponseParserTest",
    deps = [
        "//java/de/ofahrt/catfish/client/legacy",
        "//java/de/ofahrt/catfish/model",
        "//javatest/de/ofahrt/catfish/client:test_helper",
    ],
)
