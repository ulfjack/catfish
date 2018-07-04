package(default_visibility = ["//visibility:public"])

java_binary(
  name = "example",
  srcs = glob(["*.java"]),
  main_class = "de.ofahrt.catfish.example.ExampleMain",
  deps = [
    "//java/de/ofahrt/catfish",
    "//java/de/ofahrt/catfish/bridge",
    "//java/de/ofahrt/catfish/fastcgi",
    "//java/de/ofahrt/catfish/model",
    "//java/de/ofahrt/catfish/model/server",
    "//java/de/ofahrt/catfish/servlets",
    "//java/de/ofahrt/catfish/ssl",
    "//java/de/ofahrt/catfish/upload",
    "//java/de/ofahrt/catfish/utils",
  ],
)
