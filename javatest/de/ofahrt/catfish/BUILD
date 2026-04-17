load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

exports_files([
    "test-cert.pem",
    "test-key.pem",
])

java_library(
    name = "test_helpers",
    testonly = True,
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = [
        "CollectionsUtils.java",
        "HashConflictGenerator.java",
        "HttpParserTest.java",
        "InputStreams.java",
        "RawHttpConnection.java",
        "TestServlet.java",
    ],
    visibility = ["//javatest/de/ofahrt/catfish:__subpackages__"],
    deps = [
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/client/legacy",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/utils",
        "//third_party/jspecify",
        "//third_party/junit",
        "//third_party/servlet-api",
    ],
)

java_binary(
    name = "blob_server",
    testonly = True,
    srcs = ["BlobServer.java"],
    main_class = "de.ofahrt.catfish.BlobServer",
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//third_party/jspecify",
    ],
)

java_binary(
    name = "benchmark",
    testonly = True,
    srcs = [
        "ChunkedBodyScannerBenchmark.java",
        "HttpServerBenchmark.java",
        "IncrementalHttpRequestParserBenchmark.java",
    ],
    # Disable NullAway for JMH-generated code which cannot be annotated.
    javacopts = ["-Xep:NullAway:OFF"],
    main_class = "org.openjdk.jmh.Main",
    plugins = ["//third_party/jmh:jmh_annprocess"],
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//third_party/jmh",
    ],
)

java_test(
    name = "catfish",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(
        ["*.java"],
        exclude = [
            "BlobServer.java",
            "ChunkedBodyScannerBenchmark.java",
            "HttpServerBenchmark.java",
            "CollectionsUtils.java",
            "HashConflictGenerator.java",
            "HttpParserTest.java",
            "IncrementalHttpRequestParserBenchmark.java",
            "InputStreams.java",
            "RawHttpConnection.java",
            "TestServlet.java",
        ],
    ),
    test_class = "de.ofahrt.catfish.CatfishTestSuite",
    classpath_resources = [
        "test-cert.pem",
        "test-key.pem",
    ],
    deps = [
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/http",
        "//java/de/ofahrt/catfish/internal/network",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/ssl",
        "//java/de/ofahrt/catfish/upload",
        "//java/de/ofahrt/catfish/utils",
        ":test_helpers",
        "//javatest/de/ofahrt/catfish/bridge:test_helper",
        "//javatest/de/ofahrt/catfish/utils:test_helper",
        "//javatest/info/adams/junit",
    ],
)
