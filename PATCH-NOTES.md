# caniOn Rebirth — Patch Notes

## Release 0.1 — Vgate iCar Pro 2S + Android 13+

### Patches

| # | Target | Change | Sites |
|---|--------|--------|-------|
| 1 | `smali/emobility/canion/scanner.smali` | `STM` → `ATMA` | 3 |
| 2 | `smali/emobility/canion/scanner.smali` | `STFCP` → `ATFCSM` | 19 |
| 3 | `smali/emobility/canion/scanner.smali` | `STFAP` → `ATFCSH` | 30 |
| 4 | `apktool.yml` | `targetSdkVersion` 29 → 33 | 1 |
| 5 | `AndroidManifest.xml` | `+BLUETOOTH_CONNECT`, `+BLUETOOTH_SCAN` | 2 |
| 6 | `AndroidManifest.xml` | `+FOREGROUND_SERVICE_CONNECTED_DEVICE`, `+FOREGROUND_SERVICE_LOCATION` | 2 |

### Why the command swap

The original app was written against the **STN1110** OBD chip dialect (OBDLink LX). Its monitor
commands — `STM` (set monitor), `STFCP` (filter clear/pass), `STFAP` (filter accept) — are
STN1110-specific. ELM327-family adapters (Vgate iCar Pro 2S and most modern clones) speak the
ELM dialect instead: `ATMA` (monitor all), `ATFCSM`, `ATFCSH`.

The shared commands (`ATZ`, `ATSP6`, `ATE0`, `ATH1`, `ATL0`, `ATS0`) succeed on both, which is why
the app *connects* to a Vgate and then goes **silent** — the first STN-only command gets no
meaningful reply.

### Verification status

| Layer | Result |
|-------|--------|
| Archive integrity | pass |
| Signature validity (v1 + v2 + v3) | pass |
| Platform install policy (`--min-sdk-version 30`) | pass |
| Device install (`adb install`) | **NOT RUN — no device available** |
| Data flow from a Vgate | **NOT RUN** |

The last two must be run before the patch is considered proven.

### Known caveats

See the "Known Caveats" section of the README. In short:

- The `ATFCSH` translation is faithful in *name* but not in *argument domain* (ELM wants a header,
  not a CAN ID).
- The app's PID parser was written against STN frame layout; `ATMA` emits ELM-format frames.
- Runtime permission grants and `foregroundServiceType` were not verified on-device.
- The STN1110 (OBDLink LX) path may have regressed.

### Signing note

Sign with `apksigner` (v1+v2+v3), never `jarsigner`. See the README for why — `jarsigner` produces
an APK that Android 11+ rejects as "Invalid package", and which additionally uses a digest algorithm
incompatible with the app's declared `minSdkVersion=14`.
