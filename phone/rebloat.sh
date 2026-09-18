#!/usr/bin/env bash
# Restore packages removed by debloat.sh.
set -e
cd "$(dirname "$0")"
while read -r p; do [ -z "$p" ] && continue
  adb shell cmd package install-existing "$p" </dev/null || true
done < packages.txt
