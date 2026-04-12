load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "api",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.api.CatfishApiTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model",
    ],
)
