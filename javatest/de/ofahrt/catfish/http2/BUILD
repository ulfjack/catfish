load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "http2",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.http2.AllTests",
    deps = [
        "//java/de/ofahrt/catfish/http2",
        "//third_party/junit",
    ],
)
