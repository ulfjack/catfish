load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "integration",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    classpath_resources = [
        "//javatest/de/ofahrt/catfish:test-cert.pem",
        "//javatest/de/ofahrt/catfish:test-key.pem",
    ],
    test_class = "de.ofahrt.catfish.integration.IntegrationTestSuite",
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/client",
        "//java/de/ofahrt/catfish/client/legacy",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//java/de/ofahrt/catfish/upload",
        "//java/de/ofahrt/catfish/utils",
        "//javatest/de/ofahrt/catfish:test_helpers",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
        "//javatest/de/ofahrt/catfish/client:test_helper",
        "//third_party/servlet-api",
    ],
)
