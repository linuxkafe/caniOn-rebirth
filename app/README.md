# caniOn Rebirth — Open-Source Client

An open-source Android OBD client for the Nissan Leaf, rebuilt from the
behaviour of the original `caniOn` app.

The project has two modules:

| Module | What it is | Depends on Android? |
|--------|-----------|---------------------|
| `core` | The protocol engine: frame splitter, Leaf signal decoder, ELM327 command sequence, transport-agnostic client | **No** — pure JVM, unit tested |
| `app` | A thin Android shell: Bluetooth SPP transport, runtime permissions, a live readout | Yes |

The split is deliberate: everything that encodes *knowledge about the car* lives
in `core` and is testable without a phone or an adapter.

## Status

| Component | State |
|-----------|-------|
| Frame splitter (`FrameSplitter`) | implemented, unit tested |
| Leaf signal decoder (`LeafCanParser`) | implemented, unit tested — 21 tests green |
| ELM327 command sequence (`Elm327InitSequence`) | implemented, unit tested |
| Transport-agnostic client (`ObdClient`) | implemented |
| Bluetooth SPP transport | implemented, **not yet tested on a device** |
| Live readout UI | minimal, **not yet tested on a device** |
| Trip / energy logging, SQLite schema | not started |

## The problem this solves

The original caniOn v152 is written against the **STN1110** OBD chip dialect.
Its live-monitor path uses `STFCP` (clear filter), `STFAP <id>, <mask>` (accept
filter) and `STM` (monitor) — commands that no ELM327-compatible adapter
implements. The result on a Vgate iCar Pro 2S or any other ELM327 dongle is a
*successful Bluetooth connection that never produces data*.

This client uses only standard ELM327 commands in the live path:
`ATSP6`, `ATH1`, `ATS0`, `ATCAF0`, `ATMA`. That works with every ELM327-family
adapter, including the STN1110 (which is a superset).

See [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for the full protocol
specification.

## Building

The `core` module is a plain Kotlin/JVM module and builds with Gradle:

```bash
cd app
./gradlew :core:test
```

The `app` module needs the Android SDK (compileSdk 34) and a JDK 17+.

## Using it

1. Pair your ELM327 adapter in the Android Bluetooth settings first.
2. Grant the Bluetooth permission when prompted (Android 12+ requires a runtime
   grant for `BLUETOOTH_CONNECT`).
3. Launch the app; it connects to the first paired device and starts streaming.

For a real product you would want a device picker rather than "the first paired
device", and a proper background service with a foreground-service type.

## Protocol notes

* **Frame layout** — `ATH1` (headers on) + `ATS0` (spaces off) gives one line
  per frame: 3 hex characters of CAN ID followed by 2 hex characters per data
  byte. A line is 17 characters (7 data bytes) or 19 characters (8 data bytes),
  terminated by CR.
* **Filter translation** — ELM327's `ATCA` accepts exactly one identifier per
  command, so the original multi-identifier `STFAP` list has no direct
  equivalent. `ATCAF0` (accept everything) plus `ATMA` is used instead; the
  decoder dispatches on the CAN ID anyway.

## Licence

Apache 2.0 for this project's own code. The original caniOn is by Emobility and
is neither included nor redistributed here — see the root README.
