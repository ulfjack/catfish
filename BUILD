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
DAT="${BUILD_WORKSPACE_DIRECTORY}/bazel-out/_coverage/_coverage_report.dat"
if [[ ! -f "$DAT" ]]; then
  echo "No coverage data found. Run first:"
  echo "  bazelisk coverage //javatest/de/ofahrt/catfish:catfish"
  exit 1
fi
awk '
  /^SF:/          { file=substr($0,4); hits=0; total=0 }
  /^DA:/          { total++; split($0,a,","); if (a[2]>0) hits++ }
  /^end_of_record/ {
    if (total>0 && file ~ /de.ofahrt.catfish/) {
      sub(/.*\\/java\\/de\\/ofahrt\\/catfish\\//, "", file)
      printf "%5.1f%%  %s\\n", hits*100/total, file
    }
  }
' "$DAT" | sort -n
"""

# `bazel run //:coverage_report` - prints per-file line coverage, sorted ascending.
# Run `bazelisk coverage //javatest/de/ofahrt/catfish:catfish` first.
sh_runner(
    name = "coverage_report",
    script = _COVERAGE_REPORT_SCRIPT,
)
