load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "ssl",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    data = [
        "test.cert.pem",
        "test.cert.san.pem",
        "test.cert.wildcard.pem",
        "test.key.pem",
    ],
    classpath_resources = [
        "//javatest/de/ofahrt/catfish:test-cert.pem",
        "//javatest/de/ofahrt/catfish:test-key.pem",
    ],
    test_class = "de.ofahrt.catfish.ssl.CatfishSslTestSuite",
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
    ],
)
