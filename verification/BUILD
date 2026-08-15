load("@rules_java//java:defs.bzl", "java_binary", "java_library")
load(":defs.bzl", "java_verification_test", "lean_library", "lean_test")

package(default_visibility = ["//visibility:private"])

# Trusted JVM subset semantics (part of the trust base; read by a human).
lean_library(
    name = "semantics",
    src = "Jvm/Semantics.lean",
)

# Domain spec for RFC 9112 chunk-size parsing (digitVal, valOf, hexValF...).
# Named by @ImportLeanPackage("ChunkedEncoding") in Chunk.java; not trust base.
lean_library(
    name = "chunked_encoding",
    src = "ChunkedEncoding.lean",
    deps = [":semantics"],
)

# Smoke test: executes `iconst_1; iconst_1; iadd; ireturn` through the
# semantics and proves the machine halts with 2 on the operand stack.
lean_test(
    name = "one_plus_one",
    src = "Examples/OnePlusOne.lean",
    deps = [":semantics"],
)

# The generator: compiled Java method -> Lean obligations. No dependencies.
java_binary(
    name = "generator",
    srcs = glob(["tool/src/*.java"]),
    main_class = "Main",
)

# The Java under verification. A normal java_library; its compiled bytecode
# (with LocalVariableTable) is what the generator reads.
java_library(
    name = "chunk_java",
    srcs = ["java/Chunk.java"],
    deps = ["//java/de/ofahrt/catfish/verify"],
)

# End-to-end: Chunk.java -> javac -> generator -> Lean -> audited.
# `Chunk.parseHexSize` is the RFC 9112 chunk-size parser; the proof is that its
# overflow guard makes 32-bit int overflow unreachable.
java_verification_test(
    name = "chunk",
    lib = ":chunk_java",
    method = "parseHexSize",
    source = "java/Chunk.java",
    proofs = glob(["proofs/Chunk_parseHexSize/*.lean"]),
    # Checked-in obligations: the file lean_test proves and humans review.
    # `:chunk_golden` fails if it drifts from what the generator emits.
    golden = "Generated/Chunk_parseHexSize.lean",
    deps = [
        ":semantics",
        ":chunked_encoding",
    ],
)

# Proves Chunk.hexVal satisfies its @Returns("hexValF c") contract -- the same
# contract parseHexSize substitutes at the call site. Branch-only (no loop); the
# short-circuit && expands to 15 paths, hence 15 obligations.
java_verification_test(
    name = "chunk_hexval",
    lib = ":chunk_java",
    method = "hexVal",
    source = "java/Chunk.java",
    proofs = glob(["proofs/Chunk_hexVal/*.lean"]),
    golden = "Generated/Chunk_hexVal.lean",
    deps = [
        ":semantics",
        ":chunked_encoding",
    ],
)
