# Installing the patched APK

## The two variants

| File | Signature schemes | SHA-256 (first 16) | Size |
|------|-------------------|--------------------|------|
| `caniOn_152_patched_20260822.apk` | v1 + v2 + v3 | `45dc4461a30d5a2b` | 3 718 346 |
| `caniOn_152_patched_v1v2.apk` | v1 + v2 | `6e6b00d9bef138b9` | 3 718 346 |

The original caniOn v152 is signed **v1 + v2**. The first variant matches that exactly plus v3;
the second is v1 + v2 only, for the rare OEM installer that mishandles a v3 signature block.

**Try the first; fall back to the second.**

## Verify the file before you install it

The most common reason an otherwise-correct APK fails to install is that it arrived
**corrupted or truncated** in transit. Check the size first — it must be exactly
**3 718 346 bytes**.

With a USB cable and `adb`:

```bash
adb shell 'stat -c %s /sdcard/Download/caniOn_152_patched_20260822.apk'
adb shell 'sha256sum /sdcard/Download/caniOn_152_patched_20260822.apk'
```

Expected:

```
3718346
45dc4461a30d5a2b58322f14761dcb6d11a52f63b62eb36df928d88b95dbe68b
```

If the size is smaller, the transfer was interrupted. Re-copy it.

## Install deterministically

`adb install` bypasses the OEM file manager and the `content://` handoff that some
installers get wrong:

```bash
adb install -r caniOn_152_patched_20260822.apk
```

If an older caniOn is present, remove it first — the signing key differs:

```bash
adb uninstall emobility.canion
adb install caniOn_152_patched_20260822.apk
```

## "A problem occurred while analysing the package"

That is `INSTALL_FAILED_INVALID_APK`: the package manager could not parse the file.
The APK in this repository has been checked against every mechanical property the
platform cares about, and it passes all of them:

| Check | Result |
|-------|--------|
| `unzip -t` (archive integrity) | pass |
| `zipalign -c 4` (alignment) | pass |
| `apksigner verify --min-sdk-version 14…35` | pass at every level |
| Binary manifest diff vs. the original | exactly one intended difference (`android:exported=true`) |
| Resource table diff vs. the original | identical — 284 names, same ids |
| `aapt dump badging` diff vs. the original | identical |
| Duplicate zip entries | none |
| `adb install` on an Android 11 (API 30) emulator | Success |
| `adb install` on an Android 15 (API 35) emulator | Success |

So if the file is intact and still rejected, the cause is on the device side. Work
down this list:

1. **The file on the phone is truncated.** Compare the byte count (3 718 346).
2. **The installer is being handed a `content://` URI it cannot re-read.** This
   happens when the APK is opened from a messaging app, a cloud-drive client or a
   browser's download handler. Copy the file to `/sdcard/Download/` with a file
   manager and open it from there, or use `adb install`.
3. **A stale partial installation of caniOn exists.** Uninstall it first.
4. **"Install unknown apps" is granted to the wrong app.** The permission must be
   granted to the *file manager* you are installing from.
5. **The device is below Android 5.0.** The APK declares `minSdkVersion 14`.

## After installing

1. Pair the OBD adapter in the Android Bluetooth settings (Classic SPP, not BLE).
2. Launch caniOn and pick the adapter from the device list.
3. The app opens its database at `/storage/emulated/0/BT_CAN/btcan.db`. If the
   directory does not exist yet, create it once (`adb shell mkdir -p
   /sdcard/BT_CAN`) or the app will log `SQLITE_CANTOPEN` and show an empty screen.

## The log to look at

```bash
adb logcat | grep -E "B4A|AndroidRuntime"
```

A healthy start shows `Activity (main) Create`, then `Activity_Resume`, and no
`B4A` lines containing `Exception`, `Error`, `Security` or `CantOpen`.
