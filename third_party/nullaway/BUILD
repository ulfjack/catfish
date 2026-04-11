load("@rules_java//java:defs.bzl", "java_import", "java_plugin")

# Visibility restricted: these jars are only for the NullAway compiler plugin.
# Do not depend on nullaway_lib directly — use the java_plugin instead.
package(default_visibility = ["//visibility:private"])

java_import(
    name = "nullaway_lib",
    jars = [
        "nullaway-0.13.1.jar",
        "dataflow-nullaway-3.49.3.jar",
        "checker-qual-3.49.3.jar",
        "guava-33.4.0-jre.jar",
        "failureaccess-1.0.2.jar",
    ],
)

java_plugin(
    name = "nullaway",
    visibility = ["//visibility:public"],
    deps = [":nullaway_lib"],
)
