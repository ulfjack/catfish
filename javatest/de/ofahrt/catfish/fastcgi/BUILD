load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "fastcgi",
    javacopts = ["-Xep:NullAway:OFF"],
    srcs = glob(["*.java"]),
    test_class = "de.ofahrt.catfish.fastcgi.FastCgiTestSuite",
    deps = [
        "//java/de/ofahrt/catfish/fastcgi",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
    ],
)
