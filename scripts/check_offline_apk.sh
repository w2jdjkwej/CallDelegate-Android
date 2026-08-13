#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

apkanalyzer manifest permissions "$APK" | tee /tmp/call-delegate-permissions.txt
if grep -q 'android.permission.INTERNET' /tmp/call-delegate-permissions.txt; then
  echo "ERROR: INTERNET permission is present" >&2
  exit 2
fi

echo "OK: APK manifest does not request INTERNET permission."
