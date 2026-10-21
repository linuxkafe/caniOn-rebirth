#!/usr/bin/env bash
# release-gate.sh — verify a patched caniOn APK before shipping it.
#
# Usage: ./release-gate.sh <apk> [min-sdk]
#
# WHY THIS EXISTS
# ---------------
# A rebuilt APK can pass every generic check and still be uninstallable.
# There are three independent validity layers, and generic tools only check
# the first two:
#
#   1. Archive integrity   — is the file a well-formed, uncorrupted zip?
#   2. Signature validity  — is it cryptographically signed by some key?
#   3. Install policy      — would THIS OS accept THIS scheme at THIS targetSdk?
#
# Layer 3 is where the classic failure lives. `apksigner verify` exits 0 on a
# v1-only APK — it reports facts about schemes, not a verdict on installability.
# Android 11+ requires APK Signature Scheme v2 for any APK whose
# targetSdkVersion is >= 30, and a v1-only APK is rejected with
# "Invalid package".
#
# Exit code 0 means all runnable layers passed. If no adb device is attached,
# the device-install layer is reported as NOT VERIFIED and you should not
# ship on the strength of this script alone.

set -euo pipefail

APK="${1:?usage: release-gate.sh <apk> [min-sdk]}"
MIN_SDK="${2:-30}"

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "==> Layer 1: archive integrity"
unzip -t "$APK" > /dev/null || fail "archive is corrupt"
echo "    ok"

echo "==> Layer 2: signature validity"
apksigner verify "$APK" > /dev/null || fail "signature does not verify"
apksigner verify --verbose "$APK" | grep -q 'v2 scheme.*: true' \
  || fail "v2 signature scheme missing (required for targetSdkVersion >= 30)"
echo "    ok"

echo "==> Layer 3: platform install policy (min-sdk-version $MIN_SDK)"
apksigner verify --min-sdk-version "$MIN_SDK" "$APK" > /dev/null \
  || fail "APK would be rejected by a device running API $MIN_SDK"
echo "    ok"

if command -v adb > /dev/null 2>&1 && adb get-state > /dev/null 2>&1; then
  echo "==> Layer 3b: device install"
  adb install -r "$APK" || fail "adb install failed"
  echo "    ok"
else
  echo "    WARN: no adb device attached — layer 3b NOT verified."
  echo "    Do not ship on the strength of this script alone."
fi

echo
echo "PASS: $APK"
sha256sum "$APK"
