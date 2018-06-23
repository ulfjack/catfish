package(default_visibility = ["//visibility:public"])

java_binary(
  name = "example",
  srcs = glob(["*.java"]),
  main_class = "de.ofahrt.catfish.example.ExampleMain",
  deps = [
    "//java/de/ofahrt/catfish",
    "//java/de/ofahrt/catfish/fastcgi",
    "//java/de/ofahrt/catfish/servlets",
    "//java/de/ofahrt/catfish/utils",
  ],
)
