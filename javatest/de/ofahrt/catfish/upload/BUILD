load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "upload",
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.upload.CatfishUploadTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/upload",
        "//javatest/info/adams/junit",
    ],
)
