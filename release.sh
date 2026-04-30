#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${OUTPUT_FILE:-}" ]]; then
  echo "ERROR: OUTPUT_FILE environment variable must be set" >&2
  exit 1
fi

bazel build //java/de/ofahrt/catfish:catfish-dist_deploy.jar
cp bazel-bin/java/de/ofahrt/catfish/catfish-dist_deploy.jar "$OUTPUT_FILE"

