# caniOn Rebirth — Patch Notes

## Release 0.2 — Vgate iCar Pro 2S + Android 13+

### Patches

| # | Target | Change | Sites |
|---|--------|--------|-------|
| 1 | `smali/emobility/canion/scanner.smali` | `STM` → `ATMA` | 3 |
| 2 | `smali/emobility/canion/scanner.smali` | `STFCP` → `ATFCSM` | 19 |
| 3 | `smali/emobility/canion/scanner.smali` | `STFAP` → `ATFCSH` | 30 |
| 4 | `apktool.yml` | `targetSdkVersion` 29 → 33 | 1 |
| 5 | `apktool.yml` | `versionName: null` → `versionName: ""` | 1 |
| 6 | `AndroidManifest.xml` | `+android:exported="true"` on `.main` | 1 |
| 7 | `AndroidManifest.xml` | `+BLUETOOTH_CONNECT`, `+BLUETOOTH_SCAN` | 2 |
| 8 | `AndroidManifest.xml` | `+FOREGROUND_SERVICE_CONNECTED_DEVICE`, `+FOREGROUND_SERVICE_LOCATION` | 2 |

### The three install/runtime blockers

Rebuilding an APK with apktool is **not byte-faithful**. Three separate defects had to be found
and fixed before the app would install and run on modern Android. Each was found by *running* the
artifact, not by inspecting it.

**1. Signature scheme (install blocker).**
`jarsigner` produces only the v1 (JAR) scheme. Android 11+ requires APK Signature Scheme v2 for
any APK with `targetSdkVersion >= 30`; a v1-only APK is rejected with "Invalid package". `jarsigner`
is additionally wrong at the v1 layer: it picks a digest algorithm (SHA-256/SHA-384) that is not
supported on API 14–20, and this app declares `minSdkVersion=14`. `apksigner` reads the manifest
and downgrades the algorithm automatically. → sign with `apksigner`, v1+v2+v3.

**2. Missing `android:exported` (install blocker on API 31+).**
Android 12+ refuses to install an app with `targetSdkVersion >= 31` whose components with intent
filters lack an explicit `android:exported`. The launcher activity `.main` has two intent filters
(MAIN/LAUNCHER and USB_DEVICE_ATTACHED) and no `exported` attribute. The original v152 escaped
this because `targetSdkVersion=29` predates the rule — **bumping the target SDK activated it**.
The launcher must be `exported="true"`.

**3. Dropped `android:versionName` (runtime crash).**
apktool records `versionName: null` in `apktool.yml` when the manifest attribute is present but
**empty**, and then omits the attribute from the rebuilt manifest. That matters here because the
app derives its string-decryption key from
`PackageManager.getPackageInfo(packageName, 0).versionName`. With the attribute absent,
`versionName` is `null` and the app dies with an NPE in `main$1.<init>` during
`initializeProcessGlobals` — before the first frame is drawn.

The fix is to restore it as an **empty string**, not a real version number: the original attribute
is `""` and the decryption key depends on the exact bytes. `versionName: ""` in `apktool.yml`
produces `android:versionName(0x0101021c)="" (Raw: "")` in the binary manifest.

### Why the command swap

The original app was written against the **STN1110** OBD chip dialect (OBDLink LX). Its monitor
commands — `STM` (set monitor), `STFCP` (filter clear/pass), `STFAP` (filter accept) — are
STN1110-specific. ELM327-family adapters (Vgate iCar Pro 2S and most modern clones) speak the
ELM dialect instead: `ATMA` (monitor all), `ATFCSM`, `ATFCSH`.

The shared commands (`ATZ`, `ATSP6`, `ATE0`, `ATH1`, `ATL0`, `ATS0`) succeed on both, which is why
the app *connects* to a Vgate and then goes **silent** — the first STN-only command gets no
meaningful reply.

### Verification performed

| Check | Result |
|-------|--------|
| `unzip -t` (archive integrity) | pass |
| `apksigner verify --min-sdk-version 30` (platform policy) | pass |
| `aapt dump xmltree` — manifest diff vs. original | only intended changes |
| `aapt dump badging` — package/version/permissions | correct |
| ZIP entry count vs. original | 220 = 220 |
| `adb install` on Android 11 (API 30) emulator | **Success** |
| App launch on API 30 emulator | **No crash**, `.main` resumed, 0 FATAL EXCEPTIONs |
| `adb install` on Android 15 (API 35) emulator | see README |
| Data flow from a real Vgate | **NOT RUN** — needs the physical adapter |

### Known caveats

See the "Known Caveats" section of the README. In short:

- The `ATFCSH` translation is faithful in *name* but not in *argument domain* (ELM wants a header,
  not a CAN ID).
- The app's PID parser was written against STN frame layout; `ATMA` emits ELM-format frames.
- Runtime permission grants and `foregroundServiceType` were not verified on a physical device.
- The STN1110 (OBDLink LX) path may have regressed.
