load("@rules_java//java:defs.bzl", "java_import", "java_plugin")

package(
    default_visibility = ["//visibility:public"],
    licenses = ["permissive"],
)

java_import(
    name = "jmh",
    testonly = True,
    jars = [
        "jmh-core-1.37.jar",
        "jmh-generator-annprocess-1.37.jar",
        "commons-math3-3.6.1.jar",
        "jopt-simple-5.0.4.jar",
    ],
)

java_plugin(
    name = "jmh_annprocess",
    testonly = True,
    generates_api = True,
    processor_class = "org.openjdk.jmh.generators.BenchmarkProcessor",
    deps = [":jmh"],
)
