# Debugging

caniOn has three independent diagnostic channels. All of them work on the
patched build without any extra tooling.

---

## 1. logcat — always on, nothing to enable

The app logs through B4A, which writes to logcat under the tag **`B4A`**.

```bash
adb logcat -s B4A                 # follow
adb logcat -d -s B4A | tail -50   # last 50 lines
adb logcat -d | grep -E "B4A|AndroidRuntime"
```

A healthy start looks like this:

```
B4A: ~i:*** Service (starter) Create ***
B4A: ~l1517629185:Service_Create
B4A: ~i:** Activity (main) Create, isFirst = true **
B4A: ~l35131074:Activity_Create: FirstTime=true
B4A: ~i:** Activity (main) Resume **
```

Anything containing `Exception`, `Error`, `Security` or `CantOpen` is a
problem. The messages are prefixed with the B4A sub name, so you can see which
part of the app failed.

Typical messages you may see and what they mean:

| Message | Meaning |
|---------|---------|
| `InitDatabase: SQLiteCantOpenDatabaseException … code 14 SQLITE_CANTOPEN` | The app could not open `/sdcard/BT_CAN/btcan.db` |
| `LoadAppSettings: RuntimeException: Object should first be initialized.` | A consequence of the above — the preferences never loaded |
| `lbl_StatusbarConnect_Click: SecurityException: Need android.permission.BLUETOOTH_CONNECT` | The Bluetooth connect permission is not granted |
| `init seq done` | The ELM327 command sequence finished successfully |
| `AStream_NewData: …` | An error while parsing the incoming frame stream |

---

## 2. The app's working directory

Everything the app writes lives in **`/sdcard/BT_CAN/`** (the app creates this
directory itself on first run):

| File | What |
|------|------|
| `btcan.db` | The SQLite database — preferences, odometer, cell readings, trip logs |
| `SecretKey4CarState.txt` | The key used to encrypt the "car state" it can upload |
| `about.html` | The About page |
| `trip_<date>_<time>.html` | A rendered trip report with a map |
| `log_main_<date>_<time>.txt` | The main log (see below) |
| `log_scanner_<date>_<time>.txt` | The scanner/CAN log (see below) |

Pull them off the device with:

```bash
adb pull /sdcard/BT_CAN/
```

The database is the most useful artefact: it holds the recorded odometer, cell
voltages and temperatures, the trip timer and the per-second log. Open it with
any SQLite browser, or:

```bash
adb pull /sdcard/BT_CAN/btcan.db
sqlite3 btcan.db ".tables"
sqlite3 btcan.db "select * from PREFERENCE;"
```

---

## 3. File logging — enabled by flag files

The app writes to a log file **only if a flag file with the matching name
exists** in `/sdcard/BT_CAN/`. This is inherited from the original app and is
the intended way to switch the file log on.

```bash
adb shell touch /sdcard/BT_CAN/log_main.txt
adb shell touch /sdcard/BT_CAN/log_scanner.txt
adb shell am force-stop emobility.canion
adb shell am start -n emobility.canion/.main
```

Afterwards the app creates `log_main_<YYYY-MM-DD>_<HH-mm-ss>.txt` (and
`log_scanner_…`) and appends to them. Each line is timestamped
`HH:mm:ss.SSS`.

To collect them:

```bash
adb pull /sdcard/BT_CAN/log_main_*.txt
adb pull /sdcard/BT_CAN/log_scanner_*.txt
```

### All the flag files

| Flag file | Effect |
|-----------|--------|
| `log_main.txt` | Write the main log to `log_main_<date>_<time>.txt` |
| `log_scanner.txt` | Write the CAN/scanner log to `log_scanner_<date>_<time>.txt` |
| `diag.txt` | Enable diagnostics, level 1 |
| `diag0.txt` … `diag4.txt` | Enable diagnostics, levels 0 to 4 |
| `simul.txt` | Simulation mode |
| `test.txt` | Test mode |
| `rr2miles.txt` | Report the range in miles |
| `GpsSpeed2miles.txt` | Report GPS speed in miles |

Remove a flag file to switch that behaviour off again.

---

## 4. The car-state output

The app can publish its live car state as HTML and upload it to a shared web
server (`canion.azurewebsites.net`), with the data encrypted using the key in
`SecretKey4CarState.txt`. If you enable that in the app's settings, the
generated HTML lands in `/sdcard/BT_CAN/` as `trip_<date>_<time>.html`, and you
can read the decoded values directly from it — useful when you want to confirm
that a CAN frame is being parsed correctly without attaching to a debugger.

---

## 5. Checking the protocol yourself

The open-source client in `app/` can talk to the same adapter, so you can
compare what the original app sees with what the protocol engine sees:

```bash
./build.sh
./build/caniOn-cli --serial /dev/ttyUSB0      # a USB-OTG ELM327
./build/caniOn-cli --tcp localhost:35000      # a Bluetooth SPP bridge
```

To bridge a Bluetooth adapter to TCP on a Linux box:

```bash
sudo rfcomm bind 0 <MAC-of-the-adapter>
socat TCP-LISTEN:35000,reuseaddr,fork FILE:/dev/rfcomm0
```

The CLI prints the decoded signals as they arrive, so a mismatch between it and
the original app localises the problem to either the frame format or the
signal decoding.

---

## 6. When the install itself fails

Use `./phone-diagnose.sh <apk>` — it captures the device state, the APK's
properties, the file actually on the phone, and the exact PackageManager
failure reason in one run. See `DIAGNOSE.md`.
