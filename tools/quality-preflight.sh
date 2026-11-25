#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
git diff --check
git diff --cached --check
python3 tools/check-contract.py
python3 -B -m unittest discover -s tests -v
./gradlew --no-daemon --console=plain :core:test :core:jacocoTestReport \
  :app:createDebugUnitTestCoverageReport :app:assembleDebug :app:assembleRelease \
  :app:lintDebug :app:lintRelease detekt ktlintCheck
python3 tools/check-coverage.py
gitleaks git --redact=100 --no-banner --exit-code 1 .
echo RESULT=PASS_SBR1_LOCAL_QUALITY
