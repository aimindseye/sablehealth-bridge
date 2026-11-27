#!/usr/bin/env bash
# SAI-1 remote path; original local signing path below remains unchanged.
if [[ "${1:-}" == --remote ]]; then
    shift
    client="$HOME/.local/share/sai1/final/final.py"
    [[ -f "$client" && ! -L "$client" ]] || { echo HOLD_SAI1_FINAL_CLIENT_NOT_INSTALLED; exit 2; }
    exec python3 -I "$client" release "$@"
fi
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
[[ $# == 2 && -t 0 && -f "$1" && -n "$2" ]] || { echo 'Usage: tools/build-release.sh KEYSTORE ALIAS (interactive)'; exit 2; }
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || { echo HOLD_DIRTY_CHECKOUT; exit 2; }
commit="$(git rev-parse HEAD)"
version=0.2.1-sbr2a
code=3
out="$HOME/Downloads/sablehealth-bridge-release/$version/$commit"
[[ ! -e "$out" && ! -L "$out" ]] || { echo HOLD_OUTPUT_EXISTS; exit 2; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
signer="$sdk/build-tools/36.0.0/apksigner"
aapt="$sdk/build-tools/36.0.0/aapt2"
[[ -x "$signer" && -x "$aapt" ]] || exit 2
bash tools/quality-preflight.sh
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
[[ -s "$unsigned" ]] || exit 2
stage="$(mktemp -d)"
trap 'unset sp kp; rm -rf "$stage"' EXIT
IFS= read -rs -p 'Keystore password: ' sp; printf '\n'
IFS= read -rs -p 'Key password (Enter if same): ' kp; printf '\n'
[[ -n "$sp" ]] || exit 2
kp="${kp:-$sp}"
signed="$stage/SableHealthBridge-$version-vc$code.apk"
printf '%s\n%s\n' "$sp" "$kp" | "$signer" sign --ks "$1" --ks-key-alias "$2" --ks-pass stdin --key-pass stdin --out "$signed" "$unsigned"
unset sp kp
"$signer" verify --verbose --print-certs "$signed" > "$stage/cert.txt"
expected="$(tr -d '\r\n' < config/release-certificate.sha256)"
[[ "$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' "$stage/cert.txt")" == 1 ]] || exit 2
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$stage/cert.txt")"
[[ "$actual" == "$expected" ]] || { echo HOLD_CERTIFICATE_MISMATCH; exit 2; }
grep -Eq 'Verified using v(2|3) scheme.*: true' "$stage/cert.txt" || exit 2
"$aapt" dump badging "$signed" > "$stage/badging.txt"
grep -F "package: name='io.github.aimindseye.sablehealthbridge' versionCode='$code' versionName='$version'" "$stage/badging.txt" || exit 2
mkdir -p "$(dirname "$out")"
mkdir -m 700 "$out"
cp "$signed" "$out/"
(cd "$out" && shasum -a 256 "$(basename "$signed")" > SHA256SUMS.txt)
printf '%s\n' "$commit" > "$out/SOURCE_COMMIT.txt"
echo RESULT=PASS_SBR2A_SIGNED_RELEASE
echo "APK=$out/$(basename "$signed")"
