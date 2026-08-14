"""Minimal Bazel rules for checking core Lean 4 sources with the @lean toolchain.

The project is core-only (no Mathlib, no lake), so "check a .lean" is just
"run `lean` with LEAN_PATH pointing at the dependency .olean files". These two
rules encode exactly that; there is no external Lean ruleset to depend on.

  lean_library  compiles one module to a .olean and propagates, via LeanInfo,
                the transitive oleans plus the LEAN_PATH root directories that
                make `import A.B` resolve.
  lean_test     elaborates a module and audits `#print axioms` output for
                `sorryAx` (an open goal), surfaced as a `bazel test` target.

Module naming: a module's name is its path relative to the Bazel package, so
`//verification:Jvm/Semantics.lean` is module `Jvm.Semantics`. Bazel symlinks
each source into the sandbox and `lean` canonicalizes the input path, so we
resolve the realpath at action time and set `--root` to the real package dir;
otherwise `lean` rejects the input as "not contained in root".
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

LeanInfo = provider(
    doc = "A compiled Lean module and how to put it on a dependent's LEAN_PATH.",
    fields = {
        "oleans": "depset[File]: this .olean plus all transitive dependency oleans",
        "roots": "depset[str]: LEAN_PATH directories under which those oleans resolve",
    },
)

def _toolchain_attrs(extra):
    attrs = {
        # The lean binary itself (executable); its bundled lib/ comes via _toolchain.
        "_leanbin": attr.label(
            default = "@lean//:bin/lean",
            allow_single_file = True,
        ),
        # Whole toolchain tree, so shared libs and stdlib oleans are in the sandbox.
        "_toolchain": attr.label(default = "@lean//:toolchain"),
    }
    attrs.update(extra)
    return attrs

def _module_rel(ctx, src):
    """Path of `src` relative to its package, e.g. 'Jvm/Semantics.lean'."""
    pkg = ctx.label.package
    rel = src.short_path
    if pkg and rel.startswith(pkg + "/"):
        rel = rel[len(pkg) + 1:]
    return rel

def _dep_leanpath(ctx):
    """(oleans depset, roots depset, LEAN_PATH string) from deps."""
    oleans = depset(transitive = [d[LeanInfo].oleans for d in ctx.attr.deps])
    roots = depset(transitive = [d[LeanInfo].roots for d in ctx.attr.deps])
    return oleans, roots, ":".join(roots.to_list())

def _resolve_root(src_path, modrel):
    """Shell that sets $src_real and $root to realpaths consistent for --root."""
    return "\n".join([
        "src_real=\"$(readlink -f '%s')\"" % src_path,
        "root=\"${src_real%%/%s}\"" % modrel,
    ])

def _lean_library_impl(ctx):
    src = ctx.file.src
    modrel = _module_rel(ctx, src)
    if not modrel.endswith(".lean"):
        fail("lean_library src must be a .lean file, got %s" % src.short_path)
    olean = ctx.actions.declare_file(modrel[:-len(".lean")] + ".olean")

    # Directory that must be on a dependent's LEAN_PATH for this olean to resolve.
    root = ctx.bin_dir.path + "/" + ctx.label.package

    dep_oleans, dep_roots, leanpath = _dep_leanpath(ctx)
    prefix = ("export LEAN_PATH='%s'\n" % leanpath) if leanpath else ""
    script = "\n".join([
        "set -e",
        prefix + _resolve_root(src.path, modrel),
        "'%s' --root=\"$root\" -o '%s' \"$src_real\"" % (ctx.file._leanbin.path, olean.path),
    ])
    ctx.actions.run_shell(
        inputs = depset(direct = [src], transitive = [dep_oleans, ctx.attr._toolchain.files]),
        outputs = [olean],
        command = script,
        mnemonic = "LeanCompile",
        progress_message = "Compiling Lean module %s" % modrel,
    )

    return [
        DefaultInfo(files = depset([olean])),
        LeanInfo(
            oleans = depset(direct = [olean], transitive = [dep_oleans]),
            roots = depset(direct = [root], transitive = [dep_roots]),
        ),
    ]

lean_library = rule(
    implementation = _lean_library_impl,
    attrs = _toolchain_attrs({
        "src": attr.label(allow_single_file = [".lean"], mandatory = True),
        "deps": attr.label_list(providers = [LeanInfo]),
    }),
)

def _lean_test_impl(ctx):
    src = ctx.file.src
    modrel = _module_rel(ctx, src)
    dep_oleans, _dep_roots, leanpath = _dep_leanpath(ctx)

    # Build-time check: elaborate the module, capture output, fail on a stuck
    # elaboration or a `sorryAx` in the axiom audit. The captured axiom lines
    # become the stamp, so `bazel test --test_output=all` shows them.
    stamp = ctx.actions.declare_file(ctx.label.name + ".axioms.txt")
    prefix = ("export LEAN_PATH='%s'\n" % leanpath) if leanpath else ""
    script = "\n".join([
        "set -e",
        prefix + _resolve_root(src.path, modrel),
        "'%s' --root=\"$root\" \"$src_real\" > '%s' 2>&1 || { echo 'lean: elaboration failed'; cat '%s'; exit 1; }" % (
            ctx.file._leanbin.path, stamp.path, stamp.path,
        ),
        "if grep -q sorryAx '%s'; then echo 'audit: obligation depends on sorryAx'; cat '%s'; exit 1; fi" % (
            stamp.path, stamp.path,
        ),
    ])
    ctx.actions.run_shell(
        inputs = depset(direct = [src], transitive = [dep_oleans, ctx.attr._toolchain.files]),
        outputs = [stamp],
        command = script,
        mnemonic = "LeanCheck",
        progress_message = "Checking Lean proofs in %s" % modrel,
    )

    # The test executable just echoes the audited axioms; if the check above
    # failed, `stamp` never builds, so staging the test's runfiles fails and
    # `bazel test` reports failure.
    exe = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = exe,
        content = "#!/bin/sh\nexec cat '%s'\n" % stamp.short_path,
        is_executable = True,
    )
    return [DefaultInfo(
        executable = exe,
        runfiles = ctx.runfiles(files = [stamp]),
    )]

lean_test = rule(
    implementation = _lean_test_impl,
    test = True,
    attrs = _toolchain_attrs({
        "src": attr.label(allow_single_file = [".lean"], mandatory = True),
        "deps": attr.label_list(providers = [LeanInfo]),
    }),
)

# --- Java bytecode -> Lean obligations ------------------------------------

def _java_obligations_impl(ctx):
    # The full class jar (real bytecode + LocalVariableTable), not the header jar.
    outs = ctx.attr.lib[JavaInfo].java_outputs
    class_jar = outs[0].class_jar
    entry = ctx.attr.class_name.replace(".", "/") + ".class"

    stem = "Generated/%s_%s" % (ctx.attr.class_name.replace(".", "_"), ctx.attr.method)
    lean_out = ctx.actions.declare_file(stem + ".lean")
    map_out = ctx.actions.declare_file(stem + ".lean.map.json")

    args = [
        "--method=" + ctx.attr.method,
        "--out=" + lean_out.path,
        "--source=" + ctx.file.source.path,
    ]
    inputs = [class_jar, ctx.file.source]
    if ctx.file.calls:
        args.append("--calls=" + ctx.file.calls.path)
        inputs.append(ctx.file.calls)
    if ctx.files.proofs:
        args.append("--proofs=" + ctx.files.proofs[0].dirname)
        inputs.extend(ctx.files.proofs)
    if ctx.attr.specs:
        args.append("--specs=" + ",".join(ctx.attr.specs))

    # Extract the one class from the jar, then run the generator on it.
    script = "\n".join([
        "set -e",
        "work=\"$(mktemp -d)\"",
        "unzip -oq '%s' '%s' -d \"$work\"" % (class_jar.path, entry),
        "'%s' --class=\"$work/%s\" %s" % (
            ctx.executable._generator.path, entry, " ".join(args),
        ),
    ])
    ctx.actions.run_shell(
        inputs = depset(inputs),
        outputs = [lean_out, map_out],
        command = script,
        tools = [ctx.attr._generator[DefaultInfo].files_to_run],
        use_default_shell_env = True,
        mnemonic = "JavaObligations",
        progress_message = "Generating Lean obligations for %s.%s" % (
            ctx.attr.class_name, ctx.attr.method,
        ),
    )
    return [DefaultInfo(files = depset([lean_out]))]

_java_obligations = rule(
    implementation = _java_obligations_impl,
    attrs = {
        "lib": attr.label(providers = [JavaInfo], mandatory = True),
        "method": attr.string(mandatory = True),
        "class_name": attr.string(mandatory = True),
        "source": attr.label(allow_single_file = [".java"], mandatory = True),
        "calls": attr.label(allow_single_file = True),
        "proofs": attr.label_list(allow_files = [".lean"]),
        # Domain specs the obligations reference, as "Module=Namespace" entries;
        # the generator emits `import Module` and opens `Namespace`.
        "specs": attr.string_list(),
        "_generator": attr.label(
            default = "//verification:generator",
            executable = True,
            cfg = "exec",
        ),
    },
)

def java_verification_test(name, lib, method, source, proofs, calls = None, specs = [], deps = [], class_name = None, **kwargs):
    """Verify one method of a java_library by proving its bytecode in Lean.

    Runs the generator over `lib`'s compiled class to emit Lean obligations
    (with the tactic blocks from `proofs` spliced in), then checks + axiom-audits
    them via lean_test. `bazel test :name` is green iff every obligation closes.

    `specs` lists domain specifications as "Module=Namespace" (e.g.
    "ChunkedEncodingSpec=ChunkedEncoding"); include the matching lean_library in
    `deps`.
    """
    if class_name == None:
        base = source.rsplit("/", 1)[-1]
        class_name = base[:-len(".java")] if base.endswith(".java") else base
    gen = "_%s_obligations" % name
    _java_obligations(
        name = gen,
        lib = lib,
        method = method,
        class_name = class_name,
        source = source,
        calls = calls,
        proofs = proofs,
        specs = specs,
    )
    lean_test(name = name, src = ":" + gen, deps = deps, **kwargs)
