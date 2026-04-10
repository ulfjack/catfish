load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "servlets",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.servlets.ServletsTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/servlets",
        "//javatest/de/ofahrt/catfish/client:test_helper",
        "//javatest/de/ofahrt/catfish/validator",
        "//third_party/servlet-api",
    ],
)
