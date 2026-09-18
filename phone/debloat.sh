#!/usr/bin/env bash
# Remove bloat from a Galaxy A16 (per-user, reversible). Needs adb + an authorized device.
set -e
cd "$(dirname "$0")"
while read -r p; do [ -z "$p" ] && continue
  adb shell pm uninstall --user 0 "$p" </dev/null || true
done < packages.txt
