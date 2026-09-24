load("@rules_java//java:defs.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

# Compile-only (CLASS-retained) verification annotations and cut-point markers.
# Annotated production code depends on this at no runtime cost.
java_library(
    name = "verify",
    srcs = glob(["*.java"]),
)
