# Nissan Leaf CAN Protocol — Clean-Room Specification

> Extracted by static analysis of the caniOn v152 APK (SHA-256
> `c244880cb986d4fd7b4469f3798ca4617bad70b3ad2107add966a3f3de366fbe`) using
> `jadx-headless-mcp 0.7.1` (see `tools/Dex2Java.java`). This document is a
> **specification written from observed behaviour**, not a copy of source code.
> The original caniOn project is by Emobility and remains their property.
>
> The Leaf CAN bus layout (IDs, scaling) is a well-documented part of the
> Nissan/ZE0 EV platform and is independently documented in the EV community
> (e.g. the "Leaf CAN bus" reverse-engineering write-ups). This document
> describes *how this particular client consumes it*.

---

## 1. Physical / link layer

| Property | Value |
|----------|-------|
| Bus | Nissan Leaf CAN, 500 kbit/s, ISO 15765-4, 11-bit identifiers |
| Adapter | Any ELM327-compatible Bluetooth SPP adapter (e.g. Vgate iCar Pro 2S) |
| ELM327 protocol selector | `ATSP6` — CAN 11-bit ID, 500 kbit/s |

The Leaf uses **11-bit** CAN identifiers, so every identifier in this document
fits in three hex characters (`0x000`–`0x7FF`).

---

## 2. Frame format

The adapter is configured to emit **compact, header-on, space-off** frames:

```
ATL0     linefeeds off
ATH1     headers on
ATS0     spaces off
```

With those settings, an ELM327 in monitor mode emits one line per frame,
terminated by `CR` (0x0D):

```
<3 hex chars: CAN ID><2 hex chars per data byte>
```

| Total line length | Data bytes | Layout |
|-------------------|-----------|--------|
| **17** | 7 | `ID` + bytes at offsets 3, 5, 7, 9, 11, 13, 15 |
| **19** | 8 | `ID` + bytes at offsets 3, 5, 7, 9, 11, 13, 15, 17 |

Example: CAN ID `6FA`, payload `01 10 00 00 00 00 00` →
`6FA0110000000000` (17 chars).

The receiving side must:
1. Accumulate bytes into a line buffer.
2. On each `CR`, split the line.
3. Accept the line **only if its length is 17 or 19** — anything else is a
   partial/garbled frame and is discarded.
4. Parse the CAN ID from `line[0..2]` and the payload bytes from
   `line[3 + 2*n .. 3 + 2*n + 1]` as hex.

> This is the wire format an ELM327 `ATMA` monitor produces. The original
> client used the STN1110 `STM` command, which emits a comparable compact
> layout; the *parser* is dialect-agnostic because it only looks at the ID and
> the payload bytes.

---

## 3. Init sequence

The client sends a fixed sequence of commands at connect time. Each is
terminated with `CR`. The client sends them one at a time, waiting for the
`>` prompt between each.

| # | Command | Purpose |
|---|---------|---------|
| 0 | `\r\r` | flush the adapter's input buffer |
| 1 | `\r` | |
| 2 | `ATZ` | reset adapter (also re-initialises ELM protocol to defaults) |
| 3 | `ATSP6` | select CAN 11-bit / 500 kbit/s |
| 4 | `ATE0` | echo off |
| 5 | `ATH1` | headers on |
| 6 | `ATL0` | linefeeds off |
| 7 | `ATS0` | spaces off |
| 8 | `ATFCSH761` | flow-control header = 0x761 |
| 9 | `ATFCSD300000` | flow-control data = `30 00 00` |
| 10 | `ATFCSM1` | flow-control mode 1 |
| 11 | `ATSH761` | set header to 0x761 |
| 12 | `2101` | request payload for the 0x761 cell report |
| 13 | *clear filters* | reset CAN acceptance filters |
| 14 | *filter 0x29A* | accept frames from 0x29A |
| 15 | *filter 0x6FA* | accept frames from 0x6FA |
| 16 | *start monitor* | begin streaming frames |
| 17 | `\r\r` | flush |
| 18 | *clear filters* | reset CAN acceptance filters |
| 19 | *filter 0x210* | accept frames from 0x210 |
| 20 | *filter 0x298* | accept frames from 0x298 |
| 21 | *filter 0x346* | accept frames from 0x346 |
| 22 | *filter 0x373* | accept frames from 0x373 |
| 23 | *filter 0x374* | accept frames from 0x374 |
| 24 | *filter 0x384* | accept frames from 0x384 |
| 25 | *filter 0x389* | accept frames from 0x389 |
| 26 | *filter 0x412* | accept frames from 0x412 |
| 27 | *filter 0x418* | accept frames from 0x418 (optional) |
| 28 | *filter 0x424* | accept frames from 0x424 (optional) |
| 29 | *filter 0x6E0* | accept frames from 0x6E0 |
| 30 | *start monitor* | begin streaming frames — **this is the live monitor** |

### 3.1 Dialect note (important)

The original client mixed two dialects:

* Steps 8–12 are **pure ELM327** (`ATFCSH`, `ATFCSD`, `ATFCSM`, `ATSH`). The
  STN1110 does not implement these, so the cell-voltage/temperature request
  path was already ELM327-only.
* Steps 13–30 used **STN1110** filter/monitor commands: `STFCP` (clear filter)
  and `STFAP <id>, <mask>` (accept filter), terminated by `STM` (monitor).

There is **no direct ELM327 equivalent of `STFAP`** for a *list* of accepted
identifiers: ELM's `ATCA` accepts exactly one identifier/mask pair at a time.
Three viable ELM327 translations, in order of preference:

1. **`ATMA` with no acceptance filter** (`ATCAF0` to reset filters, then
   `ATMA`). The client already dispatches on the CAN ID, so filtering is an
   optimisation, not a requirement. Simplest and most portable. Cost: more
   Bluetooth traffic.
2. **`ATCAF1` + `ATCA <id>, <mask>`** with a single mask that covers the
   required ID range. The required IDs (0x210, 0x298, 0x29A, 0x346, 0x373,
   0x374, 0x384, 0x389, 0x412, 0x418, 0x424, 0x6E0, 0x6E1–0x6E4, 0x762) are
   spread over 0x210–0x7FF, so a single ELM mask cannot cover them without
   admitting unrelated traffic.
3. **Re-assert `ATCA` per identifier** — not supported by all ELM clones.

The reference implementation in `app/` uses option 1.

---

## 4. CAN identifier map

| ID | Length | Signal(s) |
|----|--------|-----------|
| `210` | 17 | `b[2] × 0.4` → an unsigned 0.4-step value (charge power factor) |
| `298` | 19 | **Motor RPM** = `(b[6] << 8 \| b[7]) − 10000`; values in (−16, 16) are forced to 0 |
| `29A` | 19 | VIN fragment (part 2) |
| `346` | 19 | `b[7]` → a small counter/level; `b[4] & mask` → charging flag |
| `373` | 19 | **Pack current** = `((b[2] << 8 \| b[3]) − offset) / 100` A; **pack voltage** = `(b[4] << 8 \| b[5]) / 10` V; power = A × V |
| `374` | 19 | **State of charge** = `(b[1] − 10) / 2` % |
| `384` | 19 | Heater/AC: temps, AC current, heater current, AC power |
| `389` | 19 | Auxiliary battery current `b[6] / 10`; auxiliary battery voltage `b[1]` |
| `412` | 19 | **Odometer** (see §4.1) |
| `418` | 17 | Gear selector — bit flags → `P`, `R`, `N`, `D`, `B`, `C` |
| `424` | 19 | Cockpit light / blinker / high-beam bit flags |
| `6E0` | 19 | Cell block request / header frame |
| `6E1` | 19 | Cell block 1 — 8 cell voltages + 8 cell temperatures |
| `6E2` | 19 | Cell block 2 |
| `6E3` | 19 | Cell block 3 |
| `6E4` | 19 | Cell block 4 |
| `6FA` | 19 | VIN fragment (part 1) |
| `762` | 19 | **Amp-hours** = `(b[3] << 8 \| b[4]) / 10` |

### 4.1 Odometer (`412`)

```
raw   = (b[2] << 16) | (b[3] << 8) | b[4]
miles = (raw / ODOMETER_DIVISOR) × 1000
```

`ODOMETER_DIVISOR` is a vehicle-dependent constant persisted in the
`PREFERENCE` table (`SPDFACT`). The observed default is **57.75**.

Distance accumulation uses a *calibrated* factor: after each whole-odometer
increment the client refines

```
dist_coef = dist_coef / (1000 / delta_trip_units)
```

so that measured distance tracks the odometer. `dist_coef` is seeded as
`36 × ODOMETER_DIVISOR`.

### 4.2 VIN assembly (`29A` + `6FA`)

The VIN is 17 characters. It is assembled from fragments carried in the
`29A` and `6FA` frames:

* The payload of the first byte selects the fragment index (`0`, `1`, `2`).
* Index 0 → 7 characters, index 1 → 7 characters, index 2 → 3 characters.
* When the concatenation reaches 17 characters the VIN is complete and
  persisted.

### 4.3 Cell blocks (`6E1`–`6E4`)

The pack has up to **96 cells**, organised as 12 groups of 8. The group's
index is taken from payload byte 0:

```
group = b[0]                       // 1..12
if (b[0] in 0x41..0x4C) group -= 64 // alternate 12-block encoding
if (group < 1 || group > 12) -> "bad frame", discard
```

For a 4-block (`i = 1..4`) frame the payload carries 8 cell voltages and
8 cell temperatures:

```
cellIndex  = (group − 1) × 8 + (block − 1) × 2 + 1     // 1-based, pairs
voltage    = ((b[4] << 8 | b[5]) / 200) + (if > 0 then 2.1 else 0)
voltage+1  = ((b[6] << 8 | b[7]) / 200) + (if > 0 then 2.1 else 0)

temperature  = (block == 1 ? b[2] : b[1]) − 50
temperature+1= (block == 1 ? b[3] : b[2]) − 50
```

The `+2.1 V` term is a fixed offset correction applied to any non-zero
reading.

**96-cell detection.** The client collects candidate readings and decides
between a 24-cell and a 96-cell pack. A pack is treated as 96-cell when the
first block's frame header byte is `1` *and* the number of valid (non-zero)
readings matches 88 (96-cell) or 80 (24-cell) exactly. The very first
observation is used as a sanity check: if the summed cell voltage exceeds
`1.02 × pack voltage` the pack is treated as 96-cell.

In 96-cell mode, groups 6 and 12 are known to be spurious and are zeroed.

---

## 5. Rate limits and lifecycle

* The client streams continuously in monitor mode and parses every frame.
* Energy counters are accumulated per sign of current (positive / negative /
  heater / AC) and flushed to the log table once per second.
* Odometer and trip distance are re-derived whenever the odometer counter
  increments.
* A `CAN` frame whose payload carries `0xFFFF` is treated as "no AC current".

---

## 6. What this means for an open-source client

1. **Any ELM327 adapter works.** The protocol needs only `ATSP6`, `ATMA` and
   the `ATSH`/`ATFCSD` request pair. There is no vendor-specific command in
   the *live* path.
2. **The frame parser is dialect-independent** as long as the adapter emits
   `ID + hex bytes` with headers on and spaces off.
3. **The interesting part of the app is not the OBD plumbing** — it is the
   Leaf-specific signal interpretation, energy accounting, odometer
   calibration, trip logging and the Bluetooth/UI plumbing around it. That is
   where an open-source re-implementation adds the most value.
4. The original app also stores data in SQLite tables: `PREFERENCE`,
   `CELL_VOLTS`, `CELL_TEMPS`, `ODOLOG`, `ODOLOG_MILES`, `AHLOG`, `SECLOG`,
   `TRIPTIMER`. An open-source client is free to choose a better schema.
