load("@rules_java//java:defs.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "model",
    srcs = glob(["*.java"]),
    deps = [
        "//java/de/ofahrt/catfish/model/network",
        "//third_party/jspecify",
    ],
)
