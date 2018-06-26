package(default_visibility = ["//visibility:public"])

java_library(
  name = "catfish",
  srcs = glob(["*.java"]),
  deps = [
    "//java/de/ofahrt/catfish/api",
    "//java/de/ofahrt/catfish/bridge",
    "//java/de/ofahrt/catfish/model/server",
    "//java/de/ofahrt/catfish/utils",
    "//third_party/servlet-api",
  ],
)

java_binary(
  name = "catfish-dist",
  runtime_deps = [
    ":catfish",
    "//java/de/ofahrt/catfish/servlets",
  ],
)
