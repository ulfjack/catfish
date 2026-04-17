load("@rules_java//java:defs.bzl", "java_library", "java_test")

java_library(
    name = "test_helper",
    testonly = True,
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = [
        "HttpResponseParserTest.java",
        "TestingCatfishHttpClient.java",
    ],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//javatest/de/ofahrt/catfish:test_helpers",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
        "//third_party/junit",
        "//third_party/servlet-api",
    ],
)

java_test(
    name = "client",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(
        ["*.java"],
        exclude = [
            "HttpResponseParserTest.java",
            "TestingCatfishHttpClient.java",
        ],
    ),
    classpath_resources = [
        "//javatest/de/ofahrt/catfish:test-cert.pem",
        "//javatest/de/ofahrt/catfish:test-key.pem",
    ],
    test_class = "de.ofahrt.catfish.client.ClientTestSuite",
    deps = [
        ":test_helper",
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/client",
        "//java/de/ofahrt/catfish/internal/network",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//java/de/ofahrt/catfish/utils",
        "//javatest/de/ofahrt/catfish:test_helpers",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
        "//javatest/info/adams/junit",
    ],
)
