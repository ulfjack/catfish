load("@rules_java//java:defs.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "http",
    srcs = glob(["*.java"]),
    exports = ["//third_party/jspecify"],
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/utils",
        "//third_party/jspecify",
    ],
)
