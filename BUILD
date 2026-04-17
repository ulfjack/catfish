load("@rules_java//java:defs.bzl", "java_binary")
load("//:format.bzl", "sh_runner")

# Wrapper around the google-java-format all-deps jar.
# The --add-exports flags are required on JDK 16+ to grant access to internal
# javac APIs that the formatter uses for parsing.
java_binary(
    name = "google-java-format",
    main_class = "com.google.googlejavaformat.java.Main",
    jvm_flags = [
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    ],
    runtime_deps = ["//third_party/google-java-format"],
    visibility = ["//visibility:private"],
)

# $BUILD_WORKSPACE_DIRECTORY is set by Bazel when running `bazel run`, pointing
# to the repo root regardless of the caller's working directory.
# $RUNFILES_DIR/_main/ is where the java_binary wrapper script lands in runfiles
# (Bazel 9 + bzlmod uses "_main" as the canonical name for the root repo).
_FORMAT_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${RUNFILES_DIR:-$0.runfiles}"
GJF="$RUNFILES_DIR/_main/google-java-format"
cd "$BUILD_WORKSPACE_DIRECTORY"
find . -name "*.java" \\
  -not -path "./bazel-*" \\
  -not -path "./.ijwb/*" \\
  -not -path "./.idea/*" \\
  | sort \\
  | xargs "$GJF" --replace
"""

# Same as _FORMAT_SCRIPT but uses --dry-run --set-exit-if-changed so the
# script exits non-zero (status 1) if any file would be reformatted.
_FORMAT_CHECK_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${RUNFILES_DIR:-$0.runfiles}"
GJF="$RUNFILES_DIR/_main/google-java-format"
cd "$BUILD_WORKSPACE_DIRECTORY"
find . -name "*.java" \\
  -not -path "./bazel-*" \\
  -not -path "./.ijwb/*" \\
  -not -path "./.idea/*" \\
  | sort \\
  | xargs "$GJF" --dry-run --set-exit-if-changed
"""

# `bazel run //:format` - rewrites all .java files in place
sh_runner(
    name = "format",
    script = _FORMAT_SCRIPT,
    data = [":google-java-format"],
)

# `bazel run //:format.check` - exits non-zero if any file is mis-formatted
sh_runner(
    name = "format.check",
    script = _FORMAT_CHECK_SCRIPT,
    data = [":google-java-format"],
)

_COVERAGE_REPORT_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
cd "${BUILD_WORKSPACE_DIRECTORY}"
DAT="./bazel-out/_coverage/_coverage_report.dat"
if [[ ! -f "$DAT" ]]; then
  echo "No coverage data found. Run first:"
  echo "  bazelisk coverage //javatest/de/ofahrt/catfish:catfish"
  exit 1
fi
echo " Lines Branch   Func  File"
awk '
  /^SF:/   { file=substr($0,4); lh=0; lf=0; brf=0; brh=0; fnf=0; fnh=0 }
  /^DA:/   { lf++; split($0,a,","); if (a[2]>0) lh++ }
  /^BRF:/  { brf=substr($0,5) }
  /^BRH:/  { brh=substr($0,5) }
  /^FNF:/  { fnf=substr($0,5) }
  /^FNH:/  { fnh=substr($0,5) }
  /^end_of_record/ {
    if (lf>0 && file ~ /de.ofahrt.catfish/) {
      sub(/.*\\/java\\/de\\/ofahrt\\/catfish\\//, "", file)
      bp = brf>0 ? brh*100/brf : 100
      fp = fnf>0 ? fnh*100/fnf : 100
      printf "%5.1f%% %5.1f%%  %5.1f%%  %s\\n", lh*100/lf, bp, fp, file
    }
  }
' "$DAT" | sort -n
"""

# `bazel run //:coverage_report` - prints per-file line coverage, sorted ascending.
# Run `bazel coverage //javatest/...` first.
sh_runner(
    name = "coverage_report",
    script = _COVERAGE_REPORT_SCRIPT,
)

_COVERAGE_HTML_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
cd "${BUILD_WORKSPACE_DIRECTORY}"
DAT="./bazel-out/_coverage/_coverage_report.dat"
if [[ ! -f "$DAT" ]]; then
  echo "No coverage data found. Run first:"
  echo "  bazel coverage //javatest/de/ofahrt/catfish:catfish"
  exit 1
fi
if ! command -v genhtml &>/dev/null; then
  echo "genhtml not found. Install lcov (e.g. apt install lcov)."
  exit 1
fi
OUT="./.coverage"
genhtml --prefix "$(pwd)" --ignore-errors unsupported,inconsistent,source \\
  --synthesize-missing --branch-coverage -o "$OUT" "$DAT"
echo "Report written to $OUT/index.html"
"""

# `bazel run //:coverage_html` - generates an HTML coverage report in .coverage/.
# Run `bazel coverage //javatest/...` first.
sh_runner(
    name = "coverage_html",
    script = _COVERAGE_HTML_SCRIPT,
)

_CPD_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${RUNFILES_DIR:-$0.runfiles}"
# Find pmd binary in runfiles (handles bzlmod repo naming)
PMD=$(find "$RUNFILES_DIR" -path "*/bin/pmd" | head -1)
if [[ -z "$PMD" ]]; then
  echo "ERROR: pmd binary not found in runfiles" >&2
  exit 1
fi
cd "$BUILD_WORKSPACE_DIRECTORY"
"$PMD" cpd --dir java/ --minimum-tokens 100 --language java --no-fail-on-violation
"""

# `bazel run //:cpd` - runs PMD copy-paste detection on Java sources.
sh_runner(
    name = "cpd",
    script = _CPD_SCRIPT,
    data = ["@pmd//:bin/pmd"],
)
