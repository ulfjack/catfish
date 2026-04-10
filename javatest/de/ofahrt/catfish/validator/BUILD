load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "validator",
    testonly = True,
    srcs = ["HtmlValidator.java"],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//third_party/jtidy",
        "//third_party/junit",
    ],
)
