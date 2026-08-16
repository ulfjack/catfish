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
# Deliberately does NOT depend on the JVM semantics: the byte source is a
# `Nat → Int` and the size cap is a `maxLen` parameter, both supplied by the
# obligation (which binds `b := s.arr` and `maxLen := MAXI`).
lean_library(
    name = "chunked_encoding",
    src = "ChunkedEncoding.lean",
)

# Refinement: the real ChunkedBodyState control flow (advanceStep + the field
# abstraction α) refines the St state machine, proved one byte at a time and
# folded over the whole input. A design-level proof, not the bytecode proof of
# advance. lean_test audits the axioms so a `sorry` would fail the build.
lean_test(
    name = "chunked_refinement",
    src = "ChunkedRefinement.lean",
    deps = [":chunked_encoding"],
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

# A real Catfish method: de.ofahrt.catfish.http.ChunkedBodyState.hexValue. Its
# @Precondition("digitVal c ≠ none") is load-bearing -- the else branch (c-'A'+10)
# is only correct for A-F, and the precondition is what rules out non-hex input.
# `return <expr>;` returns the value on the operand stack (natural-return support).
java_verification_test(
    name = "chunkedbodystate_hexvalue",
    lib = "//java/de/ofahrt/catfish/http",
    method = "hexValue",
    class_name = "de.ofahrt.catfish.http.ChunkedBodyState",
    source = "//java/de/ofahrt/catfish/http:ChunkedBodyState.java",
    proofs = glob(["proofs/ChunkedBodyState_hexValue/*.lean"]),
    golden = "Generated/ChunkedBodyState_hexValue.lean",
    deps = [
        ":semantics",
        ":chunked_encoding",
    ],
)

# Its sibling helper: isHexDigit returns 0/1 for HEXDIG membership. Total (no
# precondition); postcondition ret = isHexDigitF c ties the boolean to digitVal.
java_verification_test(
    name = "chunkedbodystate_ishexdigit",
    lib = "//java/de/ofahrt/catfish/http",
    method = "isHexDigit",
    class_name = "de.ofahrt.catfish.http.ChunkedBodyState",
    source = "//java/de/ofahrt/catfish/http:ChunkedBodyState.java",
    proofs = glob(["proofs/ChunkedBodyState_isHexDigit/*.lean"]),
    golden = "Generated/ChunkedBodyState_isHexDigit.lean",
    deps = [
        ":semantics",
        ":chunked_encoding",
    ],
)
