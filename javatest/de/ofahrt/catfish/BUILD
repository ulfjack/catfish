load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "catfish",
    srcs = glob(["**/*.java"]),
    classpath_resources = ["test-certificate.p12"],
    data = [
        "ssl/test.key.pem",
        "ssl/test.cert.pem",
        "ssl/test.cert.san.pem",
        "ssl/test.cert.wildcard.pem",
    ],
    test_class = "de.ofahrt.catfish.CatfishTestSuite",
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/client",
        "//java/de/ofahrt/catfish/client/legacy",
        "//java/de/ofahrt/catfish/internal",
        "//java/de/ofahrt/catfish/internal/network",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/layout",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/servlets",
        "//java/de/ofahrt/catfish/ssl",
        "//java/de/ofahrt/catfish/upload",
        "//java/de/ofahrt/catfish/utils",
        "//javatest/info/adams/junit",
        "//third_party/jtidy",
        "//third_party/servlet-api",
        "//third_party/validator.nu",
    ],
)
