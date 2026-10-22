package org.caniorebirth.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.caniorebirth.leaf.LeafCanParser
import org.caniorebirth.leaf.LeafSignals
import org.caniorebirth.leaf.ObdClient

/**
 * Minimal host activity: connect to a paired adapter and display the live
 * Leaf signals.
 *
 * The heavy lifting is in `core`; this class only wires the Android
 * Bluetooth stack to it and shows the result.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var readout: TextView

    private var client: ObdClient? = null
    private var transport: BluetoothObdTransport? = null
    private val parser = LeafCanParser()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val needed = permissions()
            val missing = needed.filter { granted[it] != true }
            if (missing.isEmpty()) {
                connect()
            } else {
                status.text = "Missing permission: ${missing.joinToString { it.substringAfterLast('.') }}"
            }
        }

    private val devicePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* wired by the host */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        readout = TextView(this)
        setContentView(status)
        status.text = "Ready"
    }

    private fun permissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun connect() {
        val device = BluetoothObdTransport.candidateAdapters(this).firstOrNull()
        if (device == null) {
            status.text = "No paired Bluetooth device found. Pair your OBD adapter first."
            return
        }
        val t = BluetoothObdTransport(this, device)
        transport = t
        val c = ObdClient(t, parser)
        client = c
        t.onBytes = { bytes -> c.onBytes(bytes) }
        t.onError = { e -> status.text = "Bluetooth error: ${e.message}" }
        c.onAdapterError = { line -> status.text = "Adapter said: $line" }
        c.onFrame = { frame -> runOnUiThread { readout.text = describe(parser.signals()) } }
        status.text = "Connecting to ${device.name}…"
        c.start()
    }

    override fun onDestroy() {
        client?.stop()
        super.onDestroy()
    }

    private fun describe(s: LeafSignals): String = buildString {
        appendLine("SOC      : ${s.soc?.let { "%.0f %%".format(it) } ?: "--"}")
        appendLine("Pack V   : ${s.packVoltage?.let { "%.1f V".format(it) } ?: "--"}")
        appendLine("Pack A   : ${s.packCurrent?.let { "%.1f A".format(it) } ?: "--"}")
        appendLine("Pack W   : ${s.packPower?.let { "%.0f W".format(it) } ?: "--"}")
        appendLine("RPM      : ${s.motorRpm ?: "--"}")
        appendLine("Speed    : ${s.gear ?: "--"}")
        appendLine("Odo      : ${s.odometerMiles ?: "--"} mi")
        appendLine("Ah       : ${s.ampHours?.let { "%.1f Ah".format(it) } ?: "--"}")
        appendLine("VIN      : ${s.vin ?: "--"}")
        val cells = s.cellVoltages.count { it != null }
        appendLine("Cells    : $cells known, ${if (s.is96CellPack == true) "96-cell" else "24-cell"} pack")
    }
}
