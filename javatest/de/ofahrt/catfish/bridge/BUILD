load("@rules_java//java:defs.bzl", "java_library", "java_test")

java_library(
    name = "test_helper",
    testonly = True,
    srcs = ["TestHelper.java"],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/ssl",
        "//third_party/servlet-api",
    ],
)

java_test(
    name = "bridge",
    srcs = glob(
        ["*.java"],
        exclude = ["TestHelper.java"],
    ),
    classpath_resources = [
        "//javatest/de/ofahrt/catfish:test-cert.pem",
        "//javatest/de/ofahrt/catfish:test-key.pem",
    ],
    test_class = "de.ofahrt.catfish.bridge.CatfishBridgeTestSuite",
    deps = [
        ":test_helper",
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//javatest/de/ofahrt/catfish:test_helpers",
        "//third_party/servlet-api",
    ],
)
