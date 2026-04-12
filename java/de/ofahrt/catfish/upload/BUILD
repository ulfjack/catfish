load("@rules_java//java:defs.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "upload",
    srcs = glob(["*.java"]),
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/utils",
        "//third_party/jspecify",
    ],
)
