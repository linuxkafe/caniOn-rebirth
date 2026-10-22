#!/usr/bin/env bash
# release-gate.sh — verify a patched caniOn APK before shipping it.
#
# Usage: ./release-gate.sh <apk> [min-sdk] [original.apk]
#
# WHY THIS EXISTS
# ---------------
# A rebuilt APK can pass every generic check and still be uninstallable, or
# install and then render an empty screen. There are three independent
# validity layers, and generic tools only check the first two:
#
#   1. Archive integrity  — is the file a well-formed, uncorrupted zip?
#   2. Signature validity — is it cryptographically signed by some key?
#   3. Platform policy   — would THIS OS accept THIS scheme at THIS targetSdk?
#
# Layer 3 is where the classic failure lives. `apksigner verify` exits 0 on a
# v1-only APK — it reports facts about schemes, not a verdict on installability.
# Android 11+ requires APK Signature Scheme v2 for any APK whose
# targetSdkVersion is >= 30, and a v1-only APK is rejected with
# "Invalid package".
#
# A fourth check is specific to this app: apktool drops a present-but-empty
# `android:versionName`, and the app derives its string-decryption key from
# `PackageInfo.versionName`. Without the attribute it dies with an NPE before
# the first frame. That is a *manifest* defect, invisible to a signature
# check.
#
# Exit code 0 means all runnable layers passed. If no adb device is attached,
# the device-install layer is reported as NOT VERIFIED and you should not
# ship on the strength of this script alone.

set -euo pipefail

APK="${1:?usage: release-gate.sh <apk> [min-sdk] [original.apk]}"
MIN_SDK="${2:-30}"
ORIGINAL="${3:-}"

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

echo "==> Layer 4: manifest"
AAPT2=""
if command -v aapt2 > /dev/null 2>&1; then
  AAPT2="aapt2"
else
  for d in "${ANDROID_HOME:-}/build-tools"/* "${ANDROID_SDK_ROOT:-}/build-tools"/* \
           "$HOME/Android/Sdk/build-tools"/* /opt/android-sdk/build-tools/* \
           "$HOME/.local/share/rea"/*; do
    [ -x "$d/aapt2" ] && { AAPT2="$d/aapt2"; break; }
  done
fi
if [ -n "$AAPT2" ]; then
  "$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml > /tmp/.rg-manifest.txt 2>/dev/null \
    || fail "the manifest could not be read"
  # The app's string-decryption key is derived from PackageInfo.versionName.
  # apktool drops the attribute when it is present but empty.
  grep -q 'android:versionName' /tmp/.rg-manifest.txt \
    || fail "android:versionName is missing — the app will crash on launch with an NPE"
  echo "    ok  (android:versionName present)"

  if [ -n "$ORIGINAL" ] && [ -f "$ORIGINAL" ]; then
    "$AAPT2" dump xmltree "$ORIGINAL" --file AndroidManifest.xml > /tmp/.rg-manifest-orig.txt 2>/dev/null
    # Ignore the line-number and offset noise: compare the attribute sets only.
    for f in /tmp/.rg-manifest.txt /tmp/.rg-manifest-orig.txt; do
      sed -E 's/ \(line=[0-9]+\)//; s/d=0x[0-9a-f]+/d=OFF/; s/=0x[0-9a-f]+/=V/; s/="[^"]*"/=S/g' "$f" \
        | grep -E '^\s+(A|E):' | sort > "$f.norm"
    done
    echo "    manifest differences against $ORIGINAL:"
    diff /tmp/.rg-manifest-orig.txt.norm /tmp/.rg-manifest.txt.norm | sed 's/^/      /' || true
    echo "    (every difference must be an intended change)"
  fi
else
  echo "    WARN: aapt2 not on PATH — the manifest checks were skipped."
fi

if command -v adb > /dev/null 2>&1 && adb get-state > /dev/null 2>&1; then
  echo "==> Layer 5: device install"
  adb install -r "$APK" || fail "adb install failed"
  echo "    ok"
else
  echo "    WARN: no adb device attached — the device-install layer was NOT verified."
  echo "    Do not ship on the strength of this script alone."
fi

echo
echo "PASS: $APK"
sha256sum "$APK"
