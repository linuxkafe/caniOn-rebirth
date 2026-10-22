# caniOn Rebirth

Community maintenance fork of **caniOn** — an Android OBD-II app for Nissan Leaf battery monitoring and diagnostics.

This project provides patch notes and build instructions to make the original caniOn compatible with modern Android versions (13+) and a wider range of Bluetooth OBD adapters, including ELM327-compatible devices such as the Vgate iCar Pro 2S.

> **Disclaimer**: This is an unofficial community project. Original caniOn is by Emobility. APK files and proprietary code are not redistributed here. See the [Legal](#legal) section.

## Features Restored / Added

- **Bluetooth adapter compatibility**: Init sequence changed from STN1110-specific commands (`STM`, `STFCP`, `STFAP`) to standard ELM327 commands (`ATMA`, `ATFCSM`, `ATFCSH`) for wider adapter support.
- **Android 13+ support**: Updated `targetSdkVersion` from 29 to 33 with required runtime permissions:
  - `BLUETOOTH_CONNECT`
  - `BLUETOOTH_SCAN`
  - `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  - `FOREGROUND_SERVICE_LOCATION`
- Maintains original Leaf-specific CAN parsing and battery monitoring functionality.

## Prerequisites

- Java 21+
- Apktool 2.11.0+
- Android SDK build-tools for signing (apksigner, zipalign)

## Build Instructions

1. Obtain the original caniOn v152 APK from official sources.
2. Decode:
   ```bash
   apktool d caniOn_152_APKPure.apk -o caniOn-decoded
   ```
3. Apply the patches (this is the whole job):
   ```bash
   ./patch.sh caniOn-decoded
   ```
   Or by hand:
   - `smali/emobility/canion/scanner.smali`:
     - `const-string v3, "STM"` → `const-string v3, "ATMA"`
     - `const-string v3, "STFCP"` → `const-string v3, "ATFCSM"`
     - `const-string v3, "STFAP` → `const-string v3, "ATFCSH`
   - `apktool.yml`:
     ```yaml
     targetSdkVersion: 33
     versionName: ""      # must stay EMPTY — see below
     ```
   - `AndroidManifest.xml`:
     - `<activity android:exported="true" android:label="caniOn152" ... android:name=".main">`
     - Add `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
       `FOREGROUND_SERVICE_LOCATION`.
4. Rebuild, align and sign:
   ```bash
   apktool b caniOn-decoded -o caniOn-unsigned.apk
   zipalign -v 4 caniOn-unsigned.apk caniOn-aligned.apk
   apksigner sign --ks debug.keystore --ks-key-alias debug \
     --ks-pass pass:android --key-pass pass:android \
     --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
     --out caniOn-signed.apk caniOn-aligned.apk
   ```
5. Verify — **do not skip this**:
   ```bash
   ./release-gate.sh caniOn-signed.apk 30
   aapt dump xmltree caniOn-signed.apk AndroidManifest.xml | grep versionName
   # must print:  A: android:versionName(0x0101021c)="" (Raw: "")
   ```

> **Critical 1**: Use `apksigner`, not `jarsigner`. `jarsigner` breaks the APK in two independent ways:
> 1. It signs only the v1 (JAR) scheme, but Android 11+ requires **APK Signature Scheme v2** for
>    any APK with `targetSdkVersion >= 30`. The installer reports **"Invalid package"**.
> 2. It picks a v1 digest algorithm (SHA-256/SHA-384) that is **not supported on API 14–20**, while
>    this app declares `minSdkVersion=14`. `apksigner` reads the manifest and downgrades the digest
>    algorithm automatically.
>
> Always confirm with `apksigner verify --verbose` that `v2 scheme: true`, and with
> `apksigner verify --min-sdk-version 30` that the platform would accept it. A plain
> `apksigner verify` exits 0 on a v1-only APK — it reports facts, not a verdict.

> **Critical 2**: `versionName: ""` must be **empty**, not a real version string. The app derives
> its string-decryption key from `PackageInfo.versionName`; the original attribute is `""` and the
> key depends on those exact bytes. See PATCH-NOTES.md.

> **Critical 3**: `android:exported="true"` on the launcher activity is mandatory once
> `targetSdkVersion >= 31`. Bumping the target SDK *activates* this requirement — the original v152
> (targetSdk 29) never needed it. Without it, Android 12+ refuses to install the app.

**Important**: If you already have caniOn installed, you must uninstall it first before installing the patched version, as the signing key will differ from the original release.

## Installing

1. **Uninstall existing caniOn** if present (this is required — the patched APK is signed with a different key than the original).
2. Enable "Install unknown apps" for your file manager.
3. Install the patched `caniOn-patched.apk`.
4. Grant Bluetooth permissions when prompted.
5. Pair your OBD adapter via Android Bluetooth settings before launching.

## Troubleshooting

**"Invalid package" error**: Three known causes, in the order you should check them:
1. **Wrong signature scheme** — the APK was signed with `jarsigner` (v1 only). Android 11+ requires APK Signature Scheme v2+ for `targetSdkVersion` 30+. Re-sign with `apksigner` and verify `Verified using v2 scheme: true`.
2. **Missing `android:exported`** — if you bumped `targetSdkVersion` to 31+, the launcher activity needs an explicit `android:exported="true"`. Check with `aapt dump xmltree`.
3. **Signature mismatch** — the patched APK uses a different key than the original release. Uninstall any existing caniOn before installing.

**App installs but crashes immediately on launch** with `NullPointerException ... main$1.<init>`: the `android:versionName` attribute was dropped by apktool. The app derives its string-decryption key from `PackageInfo.versionName`; if the attribute is missing, that value is `null`. Restore `versionName: ""` in `apktool.yml` — it must be the **empty string**, matching the original manifest.

**"Parse error: Problem parsing the package"**: the APK is corrupted in transit. Re-download and verify the SHA256 hash.

**App installs but the device list is empty or connecting throws `SecurityException`**: `BLUETOOTH_CONNECT` is a *runtime* permission on Android 12+. Declaring it in the manifest is not enough — the app must request it at runtime. Check `adb logcat | grep -i security`.

**App installs but crashes when the background service starts**: the `<service>` element needs an `android:foregroundServiceType` attribute matching the `FOREGROUND_SERVICE_*` permission you declared.

**No data from Vgate**: Clear app data after installing to force device re-discovery. Ensure you're using Classic Bluetooth SPP, not BLE.

## Known Caveats

This patch is a best-effort binary modification. The following are **unresolved**:

- **Argument-domain mismatch.** `ATFCSH` on ELM327 takes a *header* (`7E8`), not a CAN ID, and has no mask argument. A literal `STFAP 298, FFF` → `ATFCSH 298, FFF` translation is therefore not faithful. The correct ELM sequence for filtering on a CAN ID is roughly `ATFCSH 7E8` + `ATFCSH 7E8, 7E0`, with ID-level selection via `ATCA`/`ATCM`.
- **Frame format.** The app's Leaf PID parser was written against STN1110 monitor output. `ATMA` emits ELM-format frames, which may not parse identically.
- **STN-emulating firmware.** Some Vgate units partially emulate the STN command set. The patch removes that path entirely.
- **Regression risk on STN1110 adapters.** The OBDLink LX previously worked. `ATMA` is understood by the STN1110, but the `ATFCSM`/`ATFCSH` replacements are not semantically equivalent to `STFCP`/`STFAP` on that chip.

If data still does not flow, capture the raw RFCOMM exchange against the adapter and check the responses to `ATMA` / `ATFCSH` before changing anything else.

## Testing

- Pair ELM327-compatible adapter via Android Bluetooth settings.
- Clear app data on first launch to force device re-discovery.
- Verify "init seq done" in connection log and data flow for RPM, SOC, cell voltages.

## License

This repository contains only patch descriptions and build scripts. Original caniOn code remains property of its authors. Use at your own risk.

## Legal

- Do not redistribute proprietary APKs.
- Original caniOn project and trademarks remain property of Emobility.
- This fork is provided for educational purposes and personal maintenance only.

## Contributing

Pull requests for additional compatibility patches welcome. Please do not submit proprietary binaries.
