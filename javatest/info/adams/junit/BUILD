load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
    licenses = ["permissive"],
)

java_library(
    name = "junit",
    testonly = 1,
    srcs = glob(["*.java"]),
    deps = [
        "//third_party/junit",
    ],
)
