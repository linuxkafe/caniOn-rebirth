# caniOn Rebirth

Community maintenance fork of **caniOn** — an Android OBD-II app for Nissan Leaf battery monitoring and diagnostics.

This project provides patch notes and build instructions to make the original caniOn compatible with modern Android versions (13+) and a wider range of Bluetooth OBD adapters, including ELM327-compatible devices such as the Vgate iCar Pro 2S.

> **Disclaimer**: This is an unofficial community project. Original caniOn is by Emobility. APK files and proprietary code are not redistributed here. See the [Legal](#legal) section.

## Features Restored / Added

- **Bluetooth adapter compatibility**: the live-monitor path used STN1110-only commands (`STM`, `STFCP`, `STFAP`). Replaced with standard ELM327 (`ATMA`, `ATCAF0`) so every ELM327-family adapter works — including the Vgate iCar Pro 2S.
- **Android 13+ support**: `targetSdkVersion` 29 → 33, with the required runtime permissions:
  - `BLUETOOTH_CONNECT`
  - `BLUETOOTH_SCAN`
  - `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  - `FOREGROUND_SERVICE_LOCATION`
- **Open-source client**: a clean-room reimplementation of the Leaf CAN protocol in `app/`, with a
  unit-tested pure-JVM core. See [`app/README.md`](app/README.md).
- **Protocol specification**: [`docs/PROTOCOL.md`](docs/PROTOCOL.md) documents the frame format, the
  full CAN identifier map, the scaling of every signal, and the odometer calibration.

## Prerequisites

- Java 21+ (JDK 17+ for the app itself)
- Apktool 2.11.0+
- Android SDK build-tools for signing (`apksigner`, `zipalign`) — **not** bundled with apktool

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
     - `const-string v3, "STMA"` → `const-string v3, "ATMA"`
     - `const-string v3, "STFCP"` → `const-string v3, "ATCAF0"`
     - `const-string v3, "STFAP ..."` → `const-string v3, "ATCAF0"` (drop the arguments —
       `ATCAF0` takes none, and `ATCAF0 298, FFF` is a malformed command)
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

- **Runtime Bluetooth permission.** `BLUETOOTH_CONNECT` is a *runtime* permission on Android 12+. The manifest declares it, but the app predates API 23 and has no `requestPermissions` call, so on Android 12+ it will need to be granted manually (or it will throw `SecurityException` at the socket). Check `adb logcat | grep -i security`.
- **Foreground service type.** `<service android:name=".scanner"/>` has no `android:foregroundServiceType`, so the `FOREGROUND_SERVICE_*` permissions declared are currently unusable. On API 34+ a mismatch is a hard `SecurityException` in `startForeground()`.
- **More Bluetooth traffic.** The STN filter list had no ELM327 equivalent (ELM's `ATCA` accepts one identifier at a time), so the patched build accepts every frame and filters in software. See `docs/PROTOCOL.md` §3.1.
- **STN1110 regression risk.** The OBDLink LX previously worked. `ATMA` is understood by the STN1110, but the `ATCAF0` replacement is not semantically equivalent to `STFAP`/`STFCP` on that chip. Test the LX before recommending the patch to existing users.
- **Data flow unverified.** The patched APK has never been run against a physical Vgate. The protocol *format* is confirmed correct — the original client's frame parser expects exactly the `ID + hex bytes` layout an ELM327 emits with `ATH1`/`ATS0` — but end-to-end data has not been observed.

## Open-Source Client

`app/` contains a clean-room reimplementation of the Leaf CAN protocol:

* `app/core` — pure-JVM protocol engine (frame splitter, signal decoder, ELM327 command sequence),
  21 unit tests, no Android dependency.
* `app/app` — a thin Android shell: Bluetooth SPP transport, runtime permissions, live readout.

See [`app/README.md`](app/README.md) and [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

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
