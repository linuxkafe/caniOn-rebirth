package org.caniorebirth.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.caniorebirth.zev.ZevCanParser
import org.caniorebirth.zev.ZevSignals
import org.caniorebirth.zev.ObdClient
import org.caniorebirth.zev.CanBusError
import kotlinx.serialization.json.Json
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Minimal host activity: connect to a paired adapter and display the live
 * ZEV signals.
 *
 * The heavy lifting is in `core`; this class only wires the Android
 * Bluetooth stack to it and shows the result.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var readout: TextView
    private lateinit var errorsView: TextView

    private var client: ObdClient? = null
    private var transport: BluetoothObdTransport? = null
    private val parser = ZevCanParser()

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
        errorsView = TextView(this)
        
        // Create a scrollable layout with status, readout, and errors
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(status)
            addView(readout)
            addView(errorsView)
        }
        setContentView(layout)
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
        c.onFrame = { frame -> 
            val signals = parser.signals()
            runOnUiThread { readout.text = describe(parser.signals()) }
            updateErrorsView(parser.getCurrentErrors())
            sendToVeilService(parser.signals(), parser.getCurrentErrors())
        }
        status.text = "Connecting to ${device.name}…"
        c.start()
    }

    private fun sendToVeilService(signals: org.caniorebirth.zev.ZevSignals, errors: List<org.caniorebirth.zev.CanBusError>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL("http://10.0.2.2:8080/api/vehicles/VEHICLE_001/signals").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                // Convert signals and errors to JSON
                val json = kotlinx.serialization.json.Json.encodeToString(
                    org.caniorebirth.zev.ZevSignals.serializer(), parser.signals()
                )
                
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                
                connection.outputStream.use { it.write(json.toByteArray()) }
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w("VeilService", "Failed to send data: $responseCode")
                }
            } catch (e: Exception) {
                Log.w("VeilService", "Failed to send data to Veil service", e)
            }
        }
    }

    private fun updateErrorsView(errors: List<org.caniorebirth.zev.CanBusError>) {
        runOnUiThread {
            if (errors.isEmpty()) {
                errorsView.text = "No errors"
            } else {
                errorsView.text = errors.joinToString("\n") { "${it.code}: ${it.description} ${if (it.cleared) " [CLEARED]" else ""}" }
            }
        }
    }

    private fun getVehicleId(): String {
        return android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    override fun onDestroy() {
        client?.stop()
        super.onDestroy()
    }

    private fun describe(s: ZevSignals): String = buildString {
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