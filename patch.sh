#!/usr/bin/env bash
# patch.sh — build the patched caniOn APK from the original.
#
#   ./patch.sh <original.apk> [output.apk]
#
# Produces an APK that:
#   * speaks standard ELM327 instead of STN1110-only commands, so any
#     ELM327-family adapter (Vgate iCar Pro 2S, …) receives data
#   * installs and runs on Android 12/13/14/15
#
# The original APK is required (it is not redistributed here). Everything
# else is in this repository.
#
# Tooling used:
#   apktool            decode / rebuild
#   Android build-tools apksigner + zipalign
#   keytool/jarsigner  keystore (a debug keystore is generated if absent)
#
# Environment overrides:
#   APKTOOL_JAR        path to apktool.jar (or set APKTOOL on PATH)
#   BUILD_TOOLS_DIR    directory containing apksigner and zipalign
#   KEYSTORE           keystore to sign with (default ./canion-debug.keystore)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$ROOT/build/patch"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
say()  { printf '%s==>%s %s\n' "$BOLD" "$OFF" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%swarn%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '%sfail%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

ORIGINAL="${1:-}"
[ -n "$ORIGINAL" ] || { sed -n '2,20p' "$0"; exit 2; }
[ -f "$ORIGINAL" ] || die "original APK not found: $ORIGINAL"
OUTPUT="${2:-$ROOT/build/caniOn-patched.apk}"
KEYSTORE="${KEYSTORE:-$ROOT/canion-debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-canion}"
STORE_PASS="${STORE_PASS:-canion-rebirth}"

# --------------------------------------------------------------- toolchain
fetch() {
  local url="$1" out="$2"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then wget -q "$url" -O "$out"
  else die "no curl or wget available to download the tooling"; fi
}

find_apktool() {
  if [ -n "${APKTOOL_JAR:-}" ] && [ -f "$APKTOOL_JAR" ]; then echo "$APKTOOL_JAR"; return; fi
  if command -v apktool >/dev/null 2>&1; then echo "apktool"; return; fi
  for c in "$ROOT/tools/apktool.jar" "$HOME/.local/share/apktool/apktool.jar" /opt/apktool/apktool.jar; do
    [ -f "$c" ] && { echo "$c"; return; }
  done
  return 1
}

find_build_tools() {
  if [ -n "${BUILD_TOOLS_DIR:-}" ] && [ -x "$BUILD_TOOLS_DIR/apksigner" ]; then echo "$BUILD_TOOLS_DIR"; return; fi
  local roots=()
  [ -n "${ANDROID_HOME:-}" ] && roots+=("$ANDROID_HOME/build-tools")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && roots+=("$ANDROID_SDK_ROOT/build-tools")
  roots+=("$HOME/Android/Sdk/build-tools" "/opt/android-sdk/build-tools" "$ROOT/tools/build-tools")
  for r in "${roots[@]}"; do
    for d in "$r"/*; do
      [ -x "$d/apksigner" ] && { echo "$d"; return; }
    done
  done
  return 1
}

command -v java >/dev/null 2>&1 || die "java not found — install a JDK 17+"

APKTOOL="$(find_apktool || true)"
if [ -z "$APKTOOL" ]; then
  say "downloading apktool 2.11.0"
  mkdir -p "$ROOT/tools"
  fetch "https://github.com/iBotPeaches/Apktool/releases/download/v2.11.0/apktool_2.11.0.jar" \
        "$ROOT/tools/apktool.jar"
  APKTOOL="$ROOT/tools/apktool.jar"
fi

BUILD_TOOLS="$(find_build_tools || true)"
if [ -z "$BUILD_TOOLS" ]; then
  say "downloading the Android build-tools (apksigner, zipalign)"
  mkdir -p "$ROOT/tools"
  fetch "https://dl.google.com/android/repository/build-tools_r33.0.2-linux.zip" \
        "$ROOT/tools/build-tools.zip"
  rm -rf "$ROOT/tools/build-tools" "$ROOT/tools/bt"
  mkdir -p "$ROOT/tools/bt"
  unzip -q "$ROOT/tools/build-tools.zip" -d "$ROOT/tools/bt"
  rm -f "$ROOT/tools/build-tools.zip"
  FOUND="$(find "$ROOT/tools/bt" -name apksigner -type f | head -1)"
  [ -n "$FOUND" ] || die "build-tools did not contain apksigner"
  BUILD_TOOLS="$(dirname "$FOUND")"
  chmod +x "$BUILD_TOOLS/apksigner" "$BUILD_TOOLS/zipalign" 2>/dev/null || true
fi

say "apktool    : $APKTOOL"
say "build-tools: $BUILD_TOOLS"

# ------------------------------------------------------------------ decode
say "decoding $ORIGINAL"
rm -rf "$WORK"
mkdir -p "$WORK"
cp "$ORIGINAL" "$WORK/original.apk"
if [ "$APKTOOL" = "apktool" ]; then
  (cd "$WORK" && apktool d original.apk -o decoded -f) || die "decode failed"
else
  (cd "$WORK" && java -jar "$APKTOOL" d original.apk -o decoded -f) || die "decode failed"
fi
ok "decoded"

DECODED="$WORK/decoded"
SMALI="$DECODED/smali/emobility/canion/scanner.smali"
YML="$DECODED/apktool.yml"
MANIFEST="$DECODED/AndroidManifest.xml"
for f in "$SMALI" "$YML" "$MANIFEST"; do [ -f "$f" ] || die "unexpected layout: $f missing"; done

# ------------------------------------------------------- patch 1: the smali
# The live-monitor path used STN1110-only commands. ELM327 has no way to
# express a *list* of accepted CAN identifiers (ATCA takes exactly one), so
# the filter list becomes ATCAF0 ("accept everything") and the client
# dispatches on the CAN identifier in software, exactly as the original did.
say "patching the OBD command dialect (STN1110 -> ELM327)"
patch_literal() {
  local old="$1" new="$2" n
  n="$(grep -c "const-string v3, \"$old\"" "$SMALI" || true)"
  if [ "$n" -gt 0 ]; then
    sed -i "s/const-string v3, \"$old\"/const-string v3, \"$new\"/g" "$SMALI"
    printf '    %-12s -> %-12s %d site(s)\n' "$old" "$new" "$n"
  else
    printf '    %-12s -> %-12s (already patched)\n' "$old" "$new"
  fi
}
patch_literal "STM" "ATMA"
patch_literal "STMA" "ATMA"
patch_literal "STFCP" "ATCAF0"
n="$(grep -c 'const-string v3, "STFAP ' "$SMALI" || true)"
if [ "$n" -gt 0 ]; then
  # ATCAF0 takes no arguments; leaving "ATCAF0 298, FFF" would be malformed.
  sed -i 's/const-string v3, "STFAP [^"]*"/const-string v3, "ATCAF0"/g' "$SMALI"
  printf '    %-12s -> %-12s %d site(s), arguments dropped\n' "STFAP <id>" "ATCAF0" "$n"
else
  printf '    %-12s -> %-12s (already patched)\n' "STFAP <id>" "ATCAF0"
fi
if grep -q 'const-string v3, "ST' "$SMALI"; then
  die "an STN1110 command survived the patch"
fi
ok "no STN1110-only command remains"

# ------------------------------------------------- patch 2: the target SDK
# The target SDK is deliberately NOT raised.
#
#  * targetSdk >= 30 enables scoped storage, and this app stores its SQLite
#    database and log files in the shared external storage root. The database
#    then fails to open (SQLITE_CANTOPEN) and the app renders an empty UI.
#  * targetSdk >= 31 requires android:exported on every component with an
#    intent filter, and the app has no runtime-permission request code, so
#    it could not obtain BLUETOOTH_CONNECT.
#  * targetSdk <= 30 keeps the legacy storage behaviour AND makes Android
#    12+ grant BLUETOOTH_CONNECT implicitly, because the app already
#    declares BLUETOOTH and BLUETOOTH_ADMIN and does NOT declare
#    BLUETOOTH_CONNECT itself.
#
# The only manifest fix is the explicit android:exported on the launcher,
# which is harmless at targetSdk 29 and makes a later SDK bump safe.
say "patching the manifest"
if grep -q 'android:exported="true" android:label="caniOn152"' "$MANIFEST"; then
  echo "    android:exported  (already set)"
else
  sed -i 's|<activity android:label="caniOn152"|<activity android:exported="true" android:label="caniOn152"|' "$MANIFEST"
  echo "    android:exported=\"true\" on the launcher activity"
fi
ok "manifest"

# ------------------------------------------------- patch 3: versionName
# apktool records a present-but-empty android:versionName as null and then
# omits the attribute on rebuild. The app derives its string-decryption key
# from PackageInfo.versionName, so without the attribute it dies with an NPE
# during initializeProcessGlobals. It must be restored as an EMPTY string:
# the original attribute is "" and the key depends on those exact bytes.
say "restoring android:versionName"
if grep -q 'versionName: null' "$YML"; then
  sed -i 's/versionName: null/versionName: ""/' "$YML"
  echo "    versionName: null -> \"\""
elif grep -q 'versionName: ""' "$YML"; then
  echo "    versionName: \"\" (already set)"
else
  die "unexpected apktool.yml: no versionName key"
fi
ok "apktool.yml"

# --------------------------------------------------------------- keystore
if [ ! -f "$KEYSTORE" ]; then
  say "generating a signing keystore"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=caniOn Rebirth, OU=Community, O=caniOn Rebirth, L=-, ST=-, C=PT" \
    > /dev/null 2>&1 || die "keytool failed"
  ok "$(basename "$KEYSTORE")"
fi

# ----------------------------------------------------------------- rebuild
say "rebuilding"
(cd "$WORK" && java -jar "$APKTOOL" b decoded -o rebuilt-unsigned.apk) || die "rebuild failed"
ok "rebuilt-unsigned.apk"

say "aligning"
"$BUILD_TOOLS/zipalign" -f 4 "$WORK/rebuilt-unsigned.apk" "$WORK/rebuilt-aligned.apk" || die "zipalign failed"
ok "aligned"

say "signing (v1 + v2 + v3)"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$STORE_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$WORK/signed.apk" "$WORK/rebuilt-aligned.apk" || die "signing failed"
ok "signed"

mkdir -p "$(dirname "$OUTPUT")"
cp "$WORK/signed.apk" "$OUTPUT"

# ----------------------------------------------------------------- verify
say "verifying"
"$BUILD_TOOLS/apksigner" verify --min-sdk-version 30 "$OUTPUT" > /dev/null || die "the APK does not satisfy the Android 11+ signature policy"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT" | grep -q 'v2 scheme.*: true' || die "v2 signature scheme missing"
ok "signature policy (API 30+) satisfied"

# The manifest must still carry the versionName the app's decryption key needs.
if command -v aapt2 >/dev/null 2>&1; then
  aapt2 dump xmltree "$OUTPUT" --file AndroidManifest.xml | grep -q 'android:versionName' \
    || die "android:versionName is missing from the rebuilt manifest"
  ok "android:versionName present"
fi

echo
printf '%sPatched APK:%s %s\n' "$GREEN" "$OFF" "$OUTPUT"
sha256sum "$OUTPUT" | sed 's/^/  sha256  /'
echo
echo "  Install it with:  adb install -r $OUTPUT"
echo "  It replaces any installed caniOn — the signing key differs, so"
echo "  uninstall the original first if one is already present."
