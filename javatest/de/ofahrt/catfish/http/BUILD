load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "http",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.http.HttpTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/http",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/utils",
        "//javatest/de/ofahrt/catfish:test_helpers",
        "//javatest/de/ofahrt/catfish/utils:test_helper",
        "//javatest/info/adams/junit",
    ],
)
