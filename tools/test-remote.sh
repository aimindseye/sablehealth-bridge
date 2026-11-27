#!/usr/bin/env bash
# Dispatch through the independently installed, owner-reviewed SAI-1 client.
set -euo pipefail
client="${SAI1_CLIENT:-$HOME/.local/share/sai1/test-remote.sh}"
[[ -f "$client" && ! -L "$client" ]] || { echo 'HOLD_SAI1_CLIENT_NOT_INSTALLED'; exit 2; }
exec /bin/bash "$client" "$@"
