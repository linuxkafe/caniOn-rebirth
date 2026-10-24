package org.caniorebirth.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.caniorebirth.zev.ZevCanParser
import org.caniorebirth.zev.ZevSignals
import org.caniorebirth.zev.ObdClient
import org.caniorebirth.zev.CanBusError
import java.net.URL
import java.net.HttpURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var readout: TextView
    private lateinit var errorsView: TextView
    private lateinit var cellGrid: CellGridView
    private lateinit var chartSoc: SignalChartView
    private lateinit var chartVolt: SignalChartView
    private lateinit var chartCurr: SignalChartView

    private val socHistory = mutableListOf<Float>()
    private val voltHistory = mutableListOf<Float>()
    private val currHistory = mutableListOf<Float>()
    private val maxHistory = 120

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        readout = findViewById(R.id.tvReadout)
        errorsView = findViewById(R.id.tvErrors)
        cellGrid = findViewById(R.id.cellGrid)
        chartSoc = findViewById(R.id.chartSoc)
        chartVolt = findViewById(R.id.chartVolt)
        chartCurr = findViewById(R.id.chartCurr)

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
            val sig = parser.signals()
            cellGrid.setVoltages(sig.cellVoltages)
            sig.soc?.let { socHistory.add(it.toFloat()); if (socHistory.size > maxHistory) socHistory.removeAt(0) }
            sig.packVoltage?.let { voltHistory.add(it.toFloat()); if (voltHistory.size > maxHistory) voltHistory.removeAt(0) }
            sig.packCurrent?.let { currHistory.add(it.toFloat()); if (currHistory.size > maxHistory) currHistory.removeAt(0) }
            chartSoc.setData(ArrayList(socHistory), "SOC")
            chartVolt.setData(ArrayList(voltHistory), "V")
            chartCurr.setData(ArrayList(currHistory), "A")
            sendToVeilService(sig, parser.getCurrentErrors())
        }
        status.text = "Connecting to ${device.name}…"
        c.start()
    }

    private fun sendToVeilService(signals: ZevSignals, errors: List<CanBusError>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
                val host = prefs.getString("veil_host", "10.0.2.2")
                val port = prefs.getString("veil_port", "8080")
                val vehicleId = prefs.getString("vehicle_id", "VEHICLE_001")
                val url = "http://$host:$port/api/vehicles/$vehicleId/signals"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val json = """{"soc":${signals.soc ?: "null"},"packVoltage":${signals.packVoltage ?: "null"},"packCurrent":${signals.packCurrent ?: "null"},"motorRpm":${signals.motorRpm ?: "null"},"hasCanBusErrors":${signals.hasCanBusErrors}}"""

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

    private fun updateErrorsView(errors: List<CanBusError>) {
        runOnUiThread {
            if (errors.isEmpty()) {
                errorsView.text = "No errors"
            } else {
                errorsView.text = errors.joinToString("\n") { "${it.code}: ${it.description} ${if (it.cleared) " [CLEARED]" else ""}" }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_errors -> {
            startActivity(android.content.Intent(this, ErrorsActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
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
        appendLine("Gear     : ${s.gear ?: "--"}")
        appendLine("Odo      : ${s.odometerMiles ?: "--"} mi")
        appendLine("Ah       : ${s.ampHours?.let { "%.1f Ah".format(it) } ?: "--"}")
        appendLine("VIN      : ${s.vin ?: "--"}")
        val cells = s.cellVoltages.filterNotNull()
        val minV = cells.minOrNull()
        val maxV = cells.maxOrNull()
        val avgV = if (cells.isNotEmpty()) cells.average() else null
        appendLine("Cells    : ${cells.size} known, ${if (s.is96CellPack == true) "96-cell" else "24-cell"} pack")
        if (cells.isNotEmpty()) {
            appendLine("Cell V   : min ${"%.3f".format(minV)} max ${"%.3f".format(maxV)} avg ${"%.3f".format(avgV)} V")
        }
    }
}
