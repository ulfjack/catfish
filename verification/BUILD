load(":defs.bzl", "lean_library", "lean_test")

package(default_visibility = ["//visibility:private"])

# Trusted JVM subset semantics (part of the trust base; read by a human).
lean_library(
    name = "semantics",
    src = "Jvm/Semantics.lean",
)

# Smoke test: executes `iconst_1; iconst_1; iadd; ireturn` through the
# semantics and proves the machine halts with 2 on the operand stack.
lean_test(
    name = "one_plus_one",
    src = "Examples/OnePlusOne.lean",
    deps = [":semantics"],
)
