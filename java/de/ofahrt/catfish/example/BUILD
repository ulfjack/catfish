load("@rules_java//java:defs.bzl", "java_binary", "java_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "handlers",
    srcs = glob(
        ["*.java"],
        exclude = ["ExampleMain.java"],
    ),
    deps = [
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/upload",
        "//java/de/ofahrt/catfish/utils",
    ],
)

java_binary(
    name = "example",
    srcs = ["ExampleMain.java"],
    main_class = "de.ofahrt.catfish.example.ExampleMain",
    deps = [
        ":handlers",
        "//java/de/ofahrt/catfish",
        "//java/de/ofahrt/catfish/bridge",
        "//java/de/ofahrt/catfish/fastcgi",
        "//java/de/ofahrt/catfish/model",
        "//java/de/ofahrt/catfish/model/network",
        "//java/de/ofahrt/catfish/model/server",
        "//java/de/ofahrt/catfish/servlets",
        "//java/de/ofahrt/catfish/ssl",
        "//java/de/ofahrt/catfish/upload",
        "//java/de/ofahrt/catfish/utils",
    ],
)
