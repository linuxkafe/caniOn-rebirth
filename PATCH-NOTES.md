# caniOn Rebirth — Patch Notes

## Release 0.3 — Vgate iCar Pro 2S + Android 13+

### Patches

| # | Target | Change | Sites |
|---|--------|--------|-------|
| 1 | `smali/emobility/canion/scanner.smali` | `STM` → `ATMA` | 3 |
| 2 | `smali/emobility/canion/scanner.smali` | `STMA` → `ATMA` | 1 |
| 3 | `smali/emobility/canion/scanner.smali` | `STFCP` → `ATCAF0` | 18 |
| 4 | `smali/emobility/canion/scanner.smali` | `STFAP <id>, <mask>` → `ATCAF0` (arguments dropped) | 29 |
| 5 | `apktool.yml` | `targetSdkVersion` 29 → 33 | 1 |
| 6 | `apktool.yml` | `versionName: null` → `versionName: ""` | 1 |
| 7 | `AndroidManifest.xml` | `+android:exported="true"` on `.main` | 1 |
| 8 | `AndroidManifest.xml` | `+BLUETOOTH_CONNECT`, `+BLUETOOTH_SCAN` | 2 |
| 9 | `AndroidManifest.xml` | `+FOREGROUND_SERVICE_CONNECTED_DEVICE`, `+FOREGROUND_SERVICE_LOCATION` | 2 |

The original `ATFCSH761` / `ATFCSD300000` / `ATFCSM1` / `ATSH761` commands are
**ELM327 already** and are left untouched.

### Why the filter list is replaced by `ATCAF0`

The original client used STN1110 filter commands:

```
STFCP                 clear the filter list
STFAP 29A, FFF        accept frames from CAN ID 0x29A
STFAP 6FA, FFF        accept frames from CAN ID 0x6FA
...
STM                   start monitoring
```

ELM327 has **no equivalent**. `ATCA` accepts exactly one identifier (or one
identifier/mask pair) at a time; there is no multi-identifier filter list. The
mapping options are:

1. `ATCAF0` (accept everything) then `ATMA` — simplest, portable, and correct:
   the client already dispatches on the CAN identifier in software, so
   hardware-side filtering is an optimisation, not a requirement.
2. `ATCAF1` + a single `ATCA <id>, <mask>` covering the whole required ID
   range. The required IDs (0x210, 0x298, 0x29A, 0x346, 0x373, 0x374, 0x384,
   0x389, 0x412, 0x418, 0x424, 0x6E0, 0x6E1–0x6E4, 0x762) span 0x210–0x7FF and
   cannot be covered by one ELM mask without admitting unrelated traffic.
3. Re-assert `ATCA` per identifier — not supported by all ELM clones.

Option 1 is used. The cost is more Bluetooth traffic; the benefit is that the
adapter no longer has to understand a command family it does not implement.

**Note on the argument domain.** `STFAP 298, FFF` takes a CAN ID *and a bit
mask*; `ATCAF0` takes neither. The arguments are dropped, not translated —
`ATCAF0 298, FFF` is a malformed command that some adapters answer with
`ERROR`.

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
| `adb install` on Android 15 (API 35) emulator | **Success** (pre-fix build fails with `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED`) |
| App launch on API 35 emulator | **No crash**, `.main` resumed, 0 FATAL EXCEPTIONs |
| Data flow from a real Vgate | **NOT RUN** — needs the physical adapter |

### Known caveats

- `BLUETOOTH_CONNECT` is a **runtime** permission on Android 12+. The manifest declares it, but the
  app predates API 23 runtime permissions and has no `requestPermissions` call, so it will need to
  be granted manually (or the app will throw `SecurityException` at the socket).
- `<service android:name=".scanner"/>` has no `android:foregroundServiceType`, so the
  `FOREGROUND_SERVICE_*` permissions declared are currently unusable.
- The STN1110 (OBDLink LX) path may have regressed: `ATMA` is understood by the STN1110, but the
  `ATCAF0` filter replacement is not semantically equivalent to `STFAP`/`STFCP` on that chip.

