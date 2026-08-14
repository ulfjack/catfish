load("@rules_java//java:defs.bzl", "java_binary", "java_library")
load(":defs.bzl", "java_verification_test", "lean_library", "lean_test")

package(default_visibility = ["//visibility:private"])

# Trusted JVM subset semantics (part of the trust base; read by a human).
lean_library(
    name = "semantics",
    src = "Jvm/Semantics.lean",
)

# Domain spec for RFC 9112 chunk-size parsing (digitVal, valOf, hexValF...).
# Referenced by the Verify.* strings in Chunk.java; not part of the trust base.
lean_library(
    name = "chunked_encoding_spec",
    src = "ChunkedEncodingSpec.lean",
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
    srcs = [
        "java/Chunk.java",
        "java/Verify.java",
    ],
)

# End-to-end: Chunk.java -> javac -> generator -> Lean -> audited.
# `Chunk.parseHexSize` is the RFC 9112 chunk-size parser; the proof is that its
# overflow guard makes 32-bit int overflow unreachable.
java_verification_test(
    name = "chunk",
    lib = ":chunk_java",
    method = "parseHexSize",
    source = "java/Chunk.java",
    calls = "java/calls.map",
    proofs = glob(["proofs/Chunk_parseHexSize/*.lean"]),
    specs = ["ChunkedEncodingSpec=ChunkedEncoding"],
    deps = [
        ":semantics",
        ":chunked_encoding_spec",
    ],
)
