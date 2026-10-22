#!/usr/bin/env bash
# phone-diagnose.sh — capture everything the platform knows about a failed install.
#
# Usage: ./phone-diagnose.sh <apk> [serial]
#
# Run this with the phone connected over USB and the APK on the PC.
# It prints the device state, the APK's properties, and — crucially — the
# exact PackageManager failure reason, which the on-device installer hides
# behind a generic "a problem occurred while analysing the package".

set -uo pipefail

APK="${1:?usage: phone-diagnose.sh <apk> [serial]}"
SERIAL="${2:-}"

ADB="adb"
[ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

hr() { printf '\n=== %s ===\n' "$1"; }

command -v adb > /dev/null 2>&1 || { echo "adb not found — install the Android platform-tools"; exit 2; }

hr "device"
$ADB devices -l
$ADB shell getprop ro.build.version.release 2>/dev/null | sed 's/^/Android  /'
$ADB shell getprop ro.build.version.sdk        2>/dev/null | sed 's/^/API      /'
$ADB shell getprop ro.product.cpu.abi        2>/dev/null | sed 's/^/ABI      /'
$ADB shell getconf PAGE_SIZE                 2>/dev/null | sed 's/^/PAGE     /'
$ADB shell pm list packages 2>/dev/null | grep -i canion | sed 's/^/INSTALLED /' || true
echo "installed caniOn packages: $($ADB shell pm list packages 2>/dev/null | grep -ci canion)"

hr "the APK on this machine"
ls -l "$APK" | sed 's/^/  /'
sha256sum "$APK" | sed 's/^/  /'
unzip -t "$APK" > /dev/null 2>&1 && echo "  archive: ok" || echo "  archive: CORRUPT"

hr "the same file on the phone"
for p in /sdcard/Download /sdcard/Documents /sdcard; do
  f="$p/$(basename "$APK")"
  if $ADB shell "test -f '$f'" 2>/dev/null; then
    echo "  found: $f"
    $ADB shell "stat -c '%s bytes' '$f'" 2>/dev/null | sed 's/^/    size:   /'
    $ADB shell "sha256sum '$f'" 2>/dev/null | sed 's/^/    sha256: /'
  fi
done

hr "install attempt (this prints the real reason)"
$ADB logcat -c 2>/dev/null
$ADB install -r "$APK" 2>&1 | sed 's/^/  /'
echo "  ---"
$ADB logcat -d 2>/dev/null \
  | grep -iE "PackageManager|PackageInstaller|parsePackage|INSTALL_" \
  | grep -viE "PackageManagerService: Scanning|observer" \
  | tail -20 | sed 's/^/  /'

hr "after a failed install, the app's own log"
$ADB logcat -d 2>/dev/null | grep -E "B4A" | tail -25 | sed 's/^/  /'

hr "summary"
echo "  The 'Failure [...]' line above is the exact reason."
echo "  Common causes, in order of likelihood:"
echo "    1. the file on the phone is truncated  -> compare the byte counts above"
echo "    2. a stale caniOn is installed       -> adb uninstall emobility.canion"
echo "    3. the installer cannot read a content:// URI -> use 'adb install' as above"
echo "    4. 'Install unknown apps' is granted to another app"
