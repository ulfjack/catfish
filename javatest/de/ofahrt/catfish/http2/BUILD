load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "http2",
    srcs = glob(["*.java"]),
    classpath_resources = [
        "//javatest/de/ofahrt/catfish:test-cert.pem",
        "//javatest/de/ofahrt/catfish:test-key.pem",
    ],
    test_class = "de.ofahrt.catfish.http2.AllTests",
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/http2",
        "//java/de/ofahrt/catfish/internal/network",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
        "//third_party/jspecify",
        "//third_party/junit",
    ],
)
