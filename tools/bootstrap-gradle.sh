#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_repo="${1:-$HOME/Documents/projects/sablehealth}"
expected=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
actual="$(shasum -a 256 "$source_repo/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')"
[[ "$actual" == "$expected" ]] || { echo 'HOLD_WRAPPER_HASH'; exit 2; }
for file in gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar; do
  [[ -f "$source_repo/$file" && ! -L "$source_repo/$file" ]] || exit 2
  if [[ -e "$file" ]]; then cmp "$file" "$source_repo/$file" || exit 2; else cp "$source_repo/$file" "$file"; fi
done
chmod +x gradlew
echo RESULT=PASS_PINNED_GRADLE_WRAPPER
