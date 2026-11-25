#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
kotlinc core/src/main/kotlin/io/github/aimindseye/sablehealthbridge/core/SleepPolicy.kt \
  core/src/test/kotlin/io/github/aimindseye/sablehealthbridge/core/PolicyCases.kt \
  tools/offline-main.kt -include-runtime -d "$work/policy.jar"
java -jar "$work/policy.jar"
