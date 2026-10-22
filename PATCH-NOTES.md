# caniOn Rebirth — Patch Notes

## Release 0.4 — Vgate iCar Pro 2S + Android 13/14/15

The recipe below was arrived at by **running** the artefact on emulators, not by
reasoning about it. Three earlier revisions of this patch each failed in a
different way; the failures are documented here because they are the useful
part.

### Patches

| # | Target | Change | Sites |
|---|--------|--------|-------|
| 1 | `smali/emobility/canion/scanner.smali` | `STM` → `ATMA` | 3 |
| 2 | `smali/emobility/canion/scanner.smali` | `STMA` → `ATMA` | 1 |
| 3 | `smali/emobility/canion/scanner.smali` | `STFCP` → `ATCAF0` | 18 |
| 4 | `smali/emobility/canion/scanner.smali` | `STFAP <id>, <mask>` → `ATCAF0` (arguments dropped) | 29 |
| 5 | `apktool.yml` | `versionName: null` → `versionName: ""` | 1 |
| 6 | `AndroidManifest.xml` | `+android:exported="true"` on `.main` | 1 |

**`targetSdkVersion` is deliberately left at 29.** See "Why the target SDK stays
at 29" below.

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
options are:

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

### Why the target SDK stays at 29

Raising `targetSdkVersion` from 29 to 33 was tried and **reverted**, because it
broke the app in three different ways:

| Raised to | What breaks | Evidence |
|-----------|-------------|----------|
| 33 | **Scoped storage.** The app's SQLite database lives at `/storage/emulated/0/BT_CAN/btcan.db`. With scoped storage it cannot be opened. | `B4A: InitDatabase: android.database.sqlite.SQLiteCantOpenDatabaseException: unknown error (code 14 SQLITE_CANTOPEN): Could not open database` → `LoadAppSettings: RuntimeException: Object should first be initialized.` → the UI renders **empty** |
| 33 | **Runtime Bluetooth permission.** `BLUETOOTH_CONNECT` became required, and this 2018-era B4A binary has no `requestPermissions` call. | `B4A: lbl_StatusbarConnect_Click: java.lang.SecurityException: Need android.permission.BLUETOOTH_CONNECT permission ... AdapterService getBondedDevices` |
| 33 | **Foreground service types.** `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `..._LOCATION` are type-level permissions that need a matching `android:foregroundServiceType` on the service. The service has none, so the declared permissions are unusable — and declaring them without the type is worse than not declaring them. | API 34+ rejects `startForeground()` with a type mismatch |

At `targetSdk 29` the platform grants `BLUETOOTH_CONNECT` **implicitly** — but
only to apps that declare `BLUETOOTH` and `BLUETOOTH_ADMIN` and do **not**
declare `BLUETOOTH_CONNECT` themselves. Declaring it removed the implicit
grant and produced the `SecurityException` above. The patched manifest therefore
declares neither `BLUETOOTH_CONNECT` nor `BLUETOOTH_SCAN`.

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
(MAIN/LAUNCHER and USB_DEVICE_ATTACHED) and no `exported` attribute. Reproduced exactly on an
Android 15 emulator:

```
INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: Failed parse during installPackageLI:
  emobility.canion.main: Targeting S+ (version 31 and above) requires that an
  explicit value for android:exported be defined when intent filters are present
```

The patched build declares it, which is harmless at `targetSdk 29` and makes a
future SDK bump safe.

**3. Dropped `android:versionName` (runtime crash).**
apktool records `versionName: null` in `apktool.yml` when the manifest attribute is present but
**empty**, and then omits the attribute from the rebuilt manifest. That matters here because the
app derives its string-decryption key from
`PackageManager.getPackageInfo(packageName, 0).versionName`. With the attribute absent,
`versionName` is `null` and the app dies with an NPE in `main$1.<init>` during
`initializeProcessGlobals` — before the first frame.

The fix is to restore it as an **empty string**, not a real version number: the original attribute
is `""` and the decryption key depends on the exact bytes. `versionName: ""` in `apktool.yml`
produces `android:versionName(0x0101021c)="" (Raw: "")` in the binary manifest.

### Verification performed

| Check | Result |
|-------|--------|
| `unzip -t` (archive integrity) | pass |
| `apksigner verify --min-sdk-version 30` (platform policy) | pass |
| `aapt dump xmltree` — manifest diff vs. original | only intended changes |
| Resource table diff vs. original | identical (284 names, same IDs) |
| DEX class/method inventory vs. original | identical (21174 entries) |
| ZIP entry count vs. original | 220 = 220 |
| `adb install` on Android 11 (API 30) emulator | **Success** |
| `adb install` on Android 15 (API 35) emulator | **Success** (a build without the `exported` fix fails with `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED`) |
| App launch on both emulators | **No crash**, `.main` resumed, 0 FATAL EXCEPTIONs, 0 B4A errors, UI renders |
| `patch.sh` from a clean `git clone` | **Success** — produces an APK that installs and renders on the emulator |
| `build.sh` from a clean `git clone` | **Success** — 21/21 unit tests green, CLI produced |
| Data flow from a real Vgate | **NOT RUN** — needs the physical adapter |

### Known caveats

- **Data flow unverified.** The patched APK has never been run against a physical Vgate. The protocol
  *format* is confirmed correct — the original client's frame parser expects exactly the
  `ID + hex bytes` layout an ELM327 emits with `ATH1`/`ATS0` — but end-to-end data has not been
  observed.
- **More Bluetooth traffic.** The STN filter list had no ELM327 equivalent, so the patched build
  accepts every frame and filters in software. See `docs/PROTOCOL.md` §3.1.
- **STN1110 regression risk.** The OBDLink LX previously worked. `ATMA` is understood by the STN1110,
  but the `ATCAF0` replacement is not semantically equivalent to `STFAP`/`STFCP` on that chip. Test
  the LX before recommending the patch to existing users.
- **The `BT_CAN` directory.** The app expects `/storage/emulated/0/BT_CAN/` to exist. On a fresh
  device it may need to be created once before the app can open its database.
