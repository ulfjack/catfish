load("@rules_java//java:defs.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "legacy",
    srcs = glob(["*.java"]),
    exports = ["//third_party/jspecify"],
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/utils",
        "//third_party/jspecify",
    ],
)
