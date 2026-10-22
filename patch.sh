#!/usr/bin/env bash
# patch.sh — apply the caniOn Rebirth patches to a decoded caniOn APK.
#
# Usage: ./patch.sh <decoded-dir>
#
# Idempotent: safe to re-run. Reports what it changed.
#
# Patches applied:
#   1. STN1110 monitor commands -> ELM327 equivalents, in scanner.smali
#   2. targetSdkVersion 29 -> 33, in apktool.yml
#   3. android:exported="true" on the launcher activity  (Android 12+ install requirement)
#   4. restore android:versionName=""                       (apktool drops it; the app's
#                                                         string-decryption key is derived
#                                                         from PackageInfo.versionName)
#   5. Android 12+/13+ permissions, in AndroidManifest.xml
#
# See README.md for the caveats around (1) — the command *names* are translated
# but the *argument domain* is not, and that needs on-device verification.
set -euo pipefail

DECODED="${1:?usage: patch.sh <decoded-dir>}"
SMALI="$DECODED/smali/emobility/canion/scanner.smali"
YML="$DECODED/apktool.yml"
MANIFEST="$DECODED/AndroidManifest.xml"

for f in "$SMALI" "$YML" "$MANIFEST"; do
  [ -f "$f" ] || { echo "ERROR: not found: $f" >&2; exit 1; }
done

changed=0

# ---------------------------------------------------------------- 1. smali
# The live-monitor path used STN1110-only commands. ELM327 has no way to
# express a *list* of accepted CAN identifiers (ATCA takes exactly one), so
# the filter list is replaced by ATCAF0 ("accept everything") and the client
# dispatches on the CAN identifier itself, exactly as the original did.
echo "==> Patching smali (STN1110 -> ELM327 monitor commands)"
for pair in 'STM:ATMA' 'STMA:ATMA' 'STFCP:ATCAF0'; do
  old="${pair%%:*}"; new="${pair##*:}"
  n=$(grep -c "const-string v3, \"${old}\"" "$SMALI" || true)
  if [ "$n" -gt 0 ]; then
    sed -i "s/const-string v3, \"${old}\"/const-string v3, \"${new}\"/g" "$SMALI"
    echo "    ${old} -> ${new}  (${n} site(s))"
    changed=1
  else
    echo "    ${old} -> ${new}  (0 sites, already patched)"
  fi
done
# "STFAP 29A, FFF" and friends become "ATCAF0" -- the arguments are dropped,
# because ATCAF0 takes none. Leaving them in place produces a malformed
# command that some adapters answer with ERROR.
n=$(grep -c 'const-string v3, "STFAP ' "$SMALI" || true)
if [ "$n" -gt 0 ]; then
  sed -i 's/const-string v3, "STFAP [^"]*"/const-string v3, "ATCAF0"/g' "$SMALI"
  echo "    STFAP <id>, <mask> -> ATCAF0  (${n} site(s), arguments dropped)"
  changed=1
else
  echo "    STFAP <id>, <mask> -> ATCAF0  (0 sites, already patched)"
fi
echo "    (note: the original ATFCSH761 / ATFCSD300000 / ATFCSM1 / ATSH761"
echo "     commands are ELM327 already and are left untouched)"

# ---------------------------------------------------------------- 2. sdk
echo "==> Patching targetSdkVersion"
if grep -q 'targetSdkVersion: 29' "$YML"; then
  sed -i 's/targetSdkVersion: 29/targetSdkVersion: 33/' "$YML"
  echo "    29 -> 33"
  changed=1
else
  echo "    already 33 (or absent)"
fi

# ------------------------------------------------- 3. versionName (apktool bug)
# apktool records `versionName: null` when the manifest attribute is present but
# empty, and then DROPS the attribute on rebuild. The app derives its
# string-decryption key from PackageManager.getPackageInfo(pkg,0).versionName.
# With the attribute absent, versionName is null and the app dies with an NPE
# in main$1.<init> during initializeProcessGlobals.
# It must be restored as an EMPTY string, not a real version: the original
# attribute is "", and the decryption key depends on the exact bytes.
echo "==> Patching versionName (must stay an empty string)"
if grep -q 'versionName: null' "$YML"; then
  sed -i 's/versionName: null/versionName: ""/' "$YML"
  echo "    null -> \"\" (empty, matching the original manifest)"
  changed=1
else
  echo "    already set"
fi

# ---------------------------------------------------------------- 4. exported
# Android 12+ (API 31) refuses to install an app with targetSdkVersion >= 31
# whose components with intent filters lack an explicit android:exported.
# The launcher activity must be exported=true; everything else has no
# intent-filter and defaults to exported=false.
echo "==> Patching android:exported on the launcher activity"
if grep -q 'android:name=".main"' "$MANIFEST" && ! grep -q 'android:exported="true" android:label="caniOn152"'; then
  sed -i 's|<activity android:label="caniOn152"|<activity android:exported="true" android:label="caniOn152"|' "$MANIFEST"
  echo "    .main -> android:exported=\"true\""
  changed=1
else
  echo "    already set"
fi

# ---------------------------------------------------------------- 5. perms
echo "==> Patching AndroidManifest.xml permissions"
for perm in BLUETOOTH_CONNECT BLUETOOTH_SCAN FOREGROUND_SERVICE_CONNECTED_DEVICE FOREGROUND_SERVICE_LOCATION; do
  if grep -q "android.permission.${perm}" "$MANIFEST"; then
    echo "    ${perm} already present"
  else
    sed -i "s|<uses-permission android:name=\"android.permission.BLUETOOTH_ADMIN\"/>|<uses-permission android:name=\"android.permission.BLUETOOTH_ADMIN\"/>\n    <uses-permission android:name=\"android.permission.${perm}\"/>|" "$MANIFEST"
    echo "    ${perm} added"
    changed=1
  fi
done

echo
if [ "$changed" -eq 1 ]; then
  echo "Patched. Rebuild with:"
  echo "  apktool b <decoded-dir> -o caniOn-unsigned.apk"
  echo "  zipalign -v 4 caniOn-unsigned.apk caniOn-aligned.apk"
  echo "  apksigner sign --ks <keystore> --ks-key-alias <alias> \\"
  echo "    --ks-pass pass:<pass> --key-pass pass:<pass> \\"
  echo "    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \\"
  echo "    --out caniOn-signed.apk caniOn-aligned.apk"
  echo
  echo "Then VERIFY (do not skip these):"
  echo "  apksigner verify --min-sdk-version 30 caniOn-signed.apk"
  echo "  aapt dump xmltree caniOn-signed.apk AndroidManifest.xml | grep versionName"
  echo "    -> must show: A: android:versionName(0x0101021c)=\"\" (Raw: \"\")"
else
  echo "No changes needed (already patched)."
fi
