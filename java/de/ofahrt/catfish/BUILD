package(default_visibility = ["//visibility:public"])

java_library(
  name = "catfish",
  srcs = glob(["*.java"]),
  deps = [
    "//java/de/ofahrt/catfish/internal",
    "//java/de/ofahrt/catfish/internal/network",
    "//java/de/ofahrt/catfish/model",
    "//java/de/ofahrt/catfish/model/network",
    "//java/de/ofahrt/catfish/model/server",
    "//java/de/ofahrt/catfish/upload",
    "//java/de/ofahrt/catfish/utils",
  ],
)

java_binary(
  name = "catfish-dist",
  runtime_deps = [
    "//java/de/ofahrt/catfish/client",
    "//java/de/ofahrt/catfish/client/legacy",
    ":catfish",
  ],
)
