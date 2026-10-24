package org.caniorebirth.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
    private lateinit var btnConnect: android.widget.Button
    private lateinit var cellGrid: CellGridView
    private lateinit var cellTempGrid: CellGridView
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
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { loc ->
        (supportFragmentManager.findFragmentById(R.id.map) as? MapFragment)?.updatePosition(loc.latitude, loc.longitude)
    }

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
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(if (prefs.getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        readout = findViewById(R.id.tvReadout)
        errorsView = findViewById(R.id.tvErrors)
        btnConnect = findViewById(R.id.btnConnect)
        btnConnect.setOnClickListener {
            val needed = permissions()
            val missing = needed.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) connect() else requestPermissions.launch(missing.toTypedArray())
        }
        startLocationTracking()
        cellGrid = findViewById(R.id.cellGrid)
        cellTempGrid = findViewById(R.id.cellTempGrid)
        cellTempGrid.mode = CellGridMode.TEMPERATURE
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

    private fun startLocationTracking() {
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val lm = locationManager ?: return
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { locationListener.onLocationChanged(it) }
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { locationListener.onLocationChanged(it) }
            }
        } catch (e: Exception) {
            Log.w("Location", "location tracking unavailable", e)
        }
    }

    private fun connect() {
        val adapters = BluetoothObdTransport.candidateAdapters(this)
        if (adapters.isEmpty()) {
            status.text = "No paired Bluetooth device found. Pair your OBD adapter first."
            return
        }
        if (adapters.size == 1) {
            connectTo(adapters[0])
            return
        }
        // Device picker dialog
        val names = adapters.map { "${it.name} (${it.address})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select OBD adapter")
            .setItems(names) { _, which -> connectTo(adapters[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectTo(device: android.bluetooth.BluetoothDevice) {
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
            cellTempGrid.setVoltages(sig.cellTemperatures.map { it?.toDouble() })
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
        R.id.action_export -> {
            exportCsv()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        client?.stop()
        super.onDestroy()
    }

    private fun exportCsv() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = java.io.File(dir, "canion_export.csv")
            f.bufferedWriter().use { w ->
                w.write("index,soc,packVoltage,packCurrent\n")
                val n = maxOf(socHistory.size, voltHistory.size, currHistory.size)
                for (i in 0 until n) {
                    val soc = socHistory.getOrElse(i) { Float.NaN }
                    val v = voltHistory.getOrElse(i) { Float.NaN }
                    val c = currHistory.getOrElse(i) { Float.NaN }
                    w.write("$i,$soc,$v,$c\n")
                }
            }
            android.widget.Toast.makeText(this, "Exported to ${f.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun describe(s: ZevSignals): String {
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        val useF = prefs.getBoolean("use_fahrenheit", false)
        val useKm = prefs.getBoolean("use_kilometers", false)
        fun c2u(c: Double?): String? = c?.let { if (useF) "%.1f F".format(it * 9.0 / 5.0 + 32.0) else "%.1f C".format(it) }
        return buildString {
        appendLine("SOC      : ${s.soc?.let { "%.0f %%".format(it) } ?: "--"}")
        appendLine("Pack V   : ${s.packVoltage?.let { "%.1f V".format(it) } ?: "--"}")
        appendLine("Pack A   : ${s.packCurrent?.let { "%.1f A".format(it) } ?: "--"}")
        appendLine("Pack W   : ${s.packPower?.let { "%.0f W".format(it) } ?: "--"}")
        appendLine("RPM      : ${s.motorRpm ?: "--"}")
        appendLine("Gear     : ${s.gear ?: "--"}")
        val odo = s.odometerMiles?.let { if (useKm) "%.0f km".format(it * 1.60934) else "$it mi" } ?: "--"
        appendLine("Odo      : $odo")
        appendLine("Ah       : ${s.ampHours?.let { "%.1f Ah".format(it) } ?: "--"}")
        appendLine("VIN      : ${s.vin ?: "--"}")
        appendLine("Heater   : ${c2u(s.heaterReturnTempC) ?: "--"} (ret) ${c2u(s.heaterFlowTempC) ?: "--"} (flow)")
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

}
