load("@rules_java//java:defs.bzl", "java_library", "java_test")

java_library(
    name = "validator",
    testonly = True,
    srcs = [
        "HtmlValidator.java",
        "HtmlValidator2.java",
    ],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//third_party/jtidy",
        "//third_party/junit",
        "//third_party/validator.nu",
    ],
)

java_test(
    name = "validator_test",
    srcs = ["HtmlValidatorTest.java"],
    test_class = "de.ofahrt.catfish.validator.HtmlValidatorTest",
    deps = [
        ":validator",
    ],
)
