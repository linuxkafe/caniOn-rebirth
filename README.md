# caniOn Rebirth

Community maintenance fork of **caniOn** — an Android OBD-II app for Nissan Leaf battery monitoring and diagnostics.

Two things live here:

1. **An open-source client.** A clean-room reimplementation of the Leaf CAN protocol
   (`app/`), buildable from this repository alone — no original APK required.
2. **A patch script.** One command turns the original caniOn v152 APK into a
   patched one that speaks standard ELM327 and installs on modern Android.

> **Disclaimer**: This is an unofficial community project. Original caniOn is by Emobility. APK files and proprietary code are not redistributed here. See the [Legal](#legal) section.

## Quick start

```bash
git clone https://github.com/linuxkafe/caniOn-rebirth.git
cd caniOn-rebirth

# Build the open-source client (JDK 17+ only; the Kotlin compiler is
# downloaded automatically if you do not have it).
./build.sh

# Build the patched APK from the original (downloads apktool and the
# Android build-tools if they are not installed).
./patch.sh /path/to/caniOn_152_APKPure.apk
```

Both scripts are self-contained: they fetch their own tooling and verify their
own output. `build.sh` runs the unit tests as part of the build.

## What was wrong with the original

| Problem | Cause | Fix |
|---------|-------|-----|
| Connects but never receives data | The live-monitor path used STN1110-only commands (`STM`, `STFCP`, `STFAP`) that no ELM327 adapter implements | Rewrite to standard ELM327 (`ATMA`, `ATCAF0`) — the filter list is dropped, because ELM327 cannot express a *list* of accepted CAN identifiers |
| "Invalid package" on Android 11+ | The APK was signed with the v1 scheme only; Android 11+ requires APK Signature Scheme v2 for `targetSdkVersion >= 30` | Sign with `apksigner`, v1 + v2 + v3 |
| Blank screen after install | `targetSdkVersion` was raised to 33, which enables scoped storage; the app's SQLite database lives in the shared external storage and can no longer be opened | **Do not raise the target SDK** — see below |
| App cannot list Bluetooth devices on Android 12+ | Declaring `BLUETOOTH_CONNECT` in the manifest *removes* the implicit grant that legacy apps get | Do **not** declare it; at `targetSdk 29` the platform grants it implicitly |

### Why the target SDK stays at 29

This is the counter-intuitive part, and it is the difference between an app that
works and an app that opens to an empty screen.

* `targetSdk >= 30` enables **scoped storage**. This app keeps its SQLite
  database (`BT_CAN/btcan.db`) and its log files in the shared external
  storage root. With scoped storage the database fails to open
  (`SQLITE_CANTOPEN`), the preferences never load, and the UI renders empty.
* `targetSdk >= 31` requires an explicit `android:exported` on every component
  with an intent filter, and requires a **runtime** `BLUETOOTH_CONNECT` request.
  This is a 2018-era B4A binary with no `requestPermissions` call, so it could
  never obtain that permission.
* `targetSdk <= 30` keeps the legacy storage behaviour **and** makes Android
  12+ grant `BLUETOOTH_CONNECT` implicitly — but only to apps that declare
  `BLUETOOTH` and `BLUETOOTH_ADMIN` and do **not** declare
  `BLUETOOTH_CONNECT` themselves. Declaring it produced
  `SecurityException: Need android.permission.BLUETOOTH_CONNECT permission`.

So the patched APK keeps `targetSdkVersion 29` and only adds an explicit
`android:exported="true"` on the launcher activity — harmless today, and it
makes a future SDK bump safe.

## The open-source client

| Module | What it is | Needs Android? |
|--------|-----------|-----------------|
| `app/core` | The protocol engine: frame splitter, Leaf signal decoder, ELM327 command sequence, transport-agnostic client | **No** — pure JVM, 21 unit tests |
| `app/cli` | A command-line client for a serial port, a TCP bridge or stdin | **No** — pure JVM |
| `app/app` | The Android application: Bluetooth SPP transport, runtime permissions, live readout | Yes |

```bash
cd app
./build/caniOn-cli --serial /dev/ttyUSB0     # a USB-OTG ELM327
./build/caniOn-cli --tcp localhost:35000     # a Bluetooth SPP bridge
./build/caniOn-cli --stdin < capture.log     # replay a captured session
```

The protocol is specified in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the frame
format, the full CAN identifier map, the per-signal scaling, the odometer
calibration and the cell layout. That document, not the code, is the durable
artefact: it describes the *car*, so it can be reimplemented by anyone.

## Repository layout

```
build.sh              build the open-source client (JDK only)
patch.sh              build the patched APK from the original
release-gate.sh       verify a patched APK before shipping it
app/core/             protocol engine (pure JVM)
app/cli/              command-line client (pure JVM)
app/app/              Android application
docs/PROTOCOL.md      the Leaf CAN protocol specification
PATCH-NOTES.md        what each patch changes and why
tools/Dex2Java.java   the jadx driver used to produce the reference decompile
```

## Legal

- Do not redistribute proprietary APKs.
- Original caniOn project and trademarks remain property of Emobility.
- This fork is provided for educational purposes and personal maintenance only.

## Contributing

Pull requests for additional compatibility patches welcome. Please do not submit
proprietary binaries.
