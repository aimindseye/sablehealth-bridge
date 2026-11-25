#!/usr/bin/env bash
set -euo pipefail
[[ $# == 2 && -f "$2" ]] || { echo 'Usage: verify-installed.sh DEVICE_SERIAL SIGNED_APK'; exit 2; }
serial="$1"; apk="$2"; pkg=io.github.aimindseye.sablehealthbridge
adb -s "$serial" shell getprop ro.product.model
user="$(adb -s "$serial" shell am get-current-user | tr -d '\r')"
[[ "$user" =~ ^[0-9]+$ ]] || exit 2
paths="$(adb -s "$serial" shell pm path --user "$user" "$pkg" | tr -d '\r')"
[[ "$(printf '%s\n' "$paths" | grep -c '^package:')" == 1 ]] || { echo HOLD_MULTIPLE_OR_MISSING_APKS; exit 2; }
remote="${paths#package:}"
[[ "$remote" == /data/app/*/base.apk ]] || exit 2
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
adb -s "$serial" pull "$remote" "$work/installed.apk"
shasum -a 256 "$apk" "$work/installed.apk"
cmp -s "$apk" "$work/installed.apk" || { echo HOLD_INSTALLED_APK_MISMATCH; exit 2; }
echo RESULT=PASS_INSTALLED_APK_EXACT_MATCH
