package org.caniorebirth.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.bluetooth.BluetoothAdapter
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
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private var sectionCharts: android.widget.LinearLayout? = null
    private lateinit var sectionDashboard: android.widget.LinearLayout
    private lateinit var sectionCells: android.widget.LinearLayout
    private lateinit var sectionHeater: android.widget.LinearLayout
    private lateinit var sectionVehicle: android.widget.LinearLayout
    private lateinit var sectionMap: android.widget.LinearLayout
    private lateinit var tvHeater: android.widget.TextView
    private lateinit var tvCellSummary: android.widget.TextView
    private lateinit var tvGear: android.widget.TextView
    private lateinit var tvRange: android.widget.TextView
    private lateinit var tvVehicle: android.widget.TextView
    private lateinit var chartSoc: SignalChartView
    private lateinit var chartVolt: SignalChartView
    private lateinit var chartCurr: SignalChartView
    private lateinit var chartCellSpread: SignalChartView
    private lateinit var gaugeSoc: GaugeView
    private lateinit var gaugeRpm: GaugeView
    private lateinit var gaugePower: GaugeView
    private lateinit var gaugeCurrent: GaugeView
    private lateinit var gaugeAux: GaugeView
    private lateinit var gaugeAcPower: GaugeView
    private lateinit var cockpitView: CockpitView
    private lateinit var sectionCockpit: android.widget.LinearLayout

    private val socHistory = mutableListOf<Float>()
    private val voltHistory = mutableListOf<Float>()
    private val currHistory = mutableListOf<Float>()
    private val cellSpreadHistory = mutableListOf<Float>()
    private val maxHistory = 120

    private var client: ObdClient? = null
    private lateinit var historyDb: HistoryDb
    private var transport: BluetoothObdTransport? = null
    private val parser = ZevCanParser()

    companion object {
        @Volatile
        var sharedParser: ZevCanParser? = null
    }
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
        CrashLogger.install(this)
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(if (prefs.getBoolean("dark_mode", true)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        if (prefs.getBoolean("force_landscape", false)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedParser = parser
        historyDb = HistoryDb(this)
        status = findViewById(R.id.tvStatus)
        readout = findViewById(R.id.tvReadout)
        errorsView = findViewById(R.id.tvErrors)
        btnConnect = findViewById(R.id.btnConnect)
        tabLayout = findViewById(R.id.tabLayout)
        sectionCharts = findViewById(R.id.sectionCharts)
        sectionDashboard = findViewById(R.id.sectionDashboard)
        sectionCockpit = findViewById(R.id.sectionCockpit)
        cockpitView = findViewById(R.id.cockpitView)
        sectionCells = findViewById(R.id.sectionCells)
        sectionHeater = findViewById(R.id.sectionHeater)
        sectionVehicle = findViewById(R.id.sectionVehicle)
        sectionMap = findViewById(R.id.sectionMap)
        tvHeater = findViewById(R.id.tvHeater)
        tvCellSummary = findViewById(R.id.tvCellSummary)
        tvGear = findViewById(R.id.tvGear)
        tvRange = findViewById(R.id.tvRange)
        tvVehicle = findViewById(R.id.tvVehicle)
        tabLayout.addTab(tabLayout.newTab().setText("Dashboard"))
        tabLayout.addTab(tabLayout.newTab().setText("Cockpit"))
        tabLayout.addTab(tabLayout.newTab().setText("Charts"))
        tabLayout.addTab(tabLayout.newTab().setText("Cells"))
        tabLayout.addTab(tabLayout.newTab().setText("Heater"))
        tabLayout.addTab(tabLayout.newTab().setText("Vehicle"))
        tabLayout.addTab(tabLayout.newTab().setText("Map"))
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                showSection(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        showSection(0)
        // Auto-load demo data when no Bluetooth adapter is available, for validation.
        if (BluetoothObdTransport.candidateAdapters(this).isEmpty()) {
            loadDemoData()
        }
        // Auto-hide tab bar on scroll down, show on scroll up
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
        scrollView?.viewTreeObserver?.addOnScrollChangedListener {
            val sy = scrollView.scrollY
            if (sy > 50) tabLayout.animate().alpha(0f).setDuration(200).start()
            else tabLayout.animate().alpha(1f).setDuration(200).start()
        }
        btnConnect.setOnClickListener {
            val needed = permissions()
            val missing = needed.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) connect() else requestPermissions.launch(missing.toTypedArray())
        }
        startLocationTracking()
        cellGrid = findViewById(R.id.cellGrid)
        cellTempGrid = findViewById(R.id.cellTempGrid)
        cellGrid.onCellClick = { idx, v, t ->
            android.app.AlertDialog.Builder(this)
                .setTitle("Cell ${idx + 1}")
                .setMessage("Voltage: ${v?.let { "%.3f V".format(it) } ?: "--"}\nTemperature: ${t?.let { "%.1f C".format(it) } ?: "--"}")
                .setPositiveButton("OK", null)
                .show()
        }
        cellTempGrid.mode = CellGridMode.TEMPERATURE
        chartSoc = findViewById(R.id.chartSoc)
        chartVolt = findViewById(R.id.chartVolt)
        chartCurr = findViewById(R.id.chartCurr)
        chartCellSpread = findViewById(R.id.chartCellSpread)
        gaugeSoc = findViewById(R.id.gaugeSoc)
        gaugeRpm = findViewById(R.id.gaugeRpm)
        gaugeSoc.label = "SOC"
        gaugeSoc.unit = "%"
        gaugeSoc.min = 0f
        gaugeSoc.max = 100f
        gaugeRpm.label = "RPM"
        gaugeRpm.unit = "rpm"
        gaugeRpm.min = 0f
        gaugeRpm.max = 8000f
        gaugePower = findViewById(R.id.gaugePower)
        gaugeCurrent = findViewById(R.id.gaugeCurrent)
        gaugePower.label = "Power"
        gaugePower.unit = "W"
        gaugePower.min = -20000f
        gaugePower.max = 20000f
        gaugeCurrent.label = "Current"
        gaugeCurrent.unit = "A"
        gaugeCurrent.min = -150f
        gaugeCurrent.max = 150f
        gaugeAux = findViewById(R.id.gaugeAux)
        gaugeAcPower = findViewById(R.id.gaugeAcPower)
        gaugeAux.label = "Aux Batt"
        gaugeAux.unit = "V"
        gaugeAux.min = 0f
        gaugeAux.max = 20f
        gaugeAcPower.label = "AC Power"
        gaugeAcPower.unit = "W"
        gaugeAcPower.min = 0f
        gaugeAcPower.max = 5000f

        status.text = "Ready"
        tryAutoConnect()
    }

    private fun tryAutoConnect() {
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        val addr = prefs.getString("last_device", null) ?: return
        val needed = permissions()
        val missing = needed.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = try { adapter.getRemoteDevice(addr) } catch (e: Exception) { null } ?: return
        connectTo(device)
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

    private fun showSection(pos: Int) {
        sectionDashboard.visibility = if (pos == 0) android.view.View.VISIBLE else android.view.View.GONE
        sectionCockpit.visibility = if (pos == 1) android.view.View.VISIBLE else android.view.View.GONE
        sectionCharts?.visibility = if (pos == 2) android.view.View.VISIBLE else android.view.View.GONE
        sectionCells.visibility = if (pos == 3) android.view.View.VISIBLE else android.view.View.GONE
        sectionHeater.visibility = if (pos == 4) android.view.View.VISIBLE else android.view.View.GONE
        sectionVehicle.visibility = if (pos == 5) android.view.View.VISIBLE else android.view.View.GONE
        sectionMap.visibility = if (pos == 6) android.view.View.VISIBLE else android.view.View.GONE
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
        t.onError = { e -> runOnUiThread { status.text = "Bluetooth error: ${e.message}" }; sendAppError("BluetoothObdTransport", e.message ?: "unknown", e.stackTraceToString()) }
        c.onAdapterError = { line -> runOnUiThread { status.text = "Adapter said: $line" } }
        c.onFrame = { frame ->
            runOnUiThread {
                try {
                val sig = parser.signals()
                readout.text = describe(sig)
                updateErrorsView(parser.getCurrentErrors())
                cellGrid.setVoltages(sig.cellVoltages)
                cellGrid.setTemperatures(sig.cellTemperatures.map { it?.toDouble() })
                cellTempGrid.setVoltages(sig.cellTemperatures.map { it?.toDouble() })
            val cellVs = sig.cellVoltages.filterNotNull()
            val temps = sig.cellTemperatures.filterNotNull()
            sig.gear?.let { tvGear.text = it.toString() }
            sig.odometerMiles?.let { tvRange.text = "%.0f km".format(it * 1.60934) }
            tvCellSummary.text = buildString {
                append("Cells V: ")
                append("min ${cellVs.minOrNull()?.let { "%.3f".format(it) } ?: "--"}  ")
                append("max ${cellVs.maxOrNull()?.let { "%.3f".format(it) } ?: "--"}  ")
                append("avg ${if (cellVs.isNotEmpty()) "%.3f".format(cellVs.average()) else "--"}")
                append("   |   T: ")
                append("min ${temps.minOrNull()?.let { "%.0f".format(it) } ?: "--"}  ")
                append("max ${temps.maxOrNull()?.let { "%.0f".format(it) } ?: "--"}  ")
                append("avg ${if (temps.isNotEmpty()) "%.0f".format(temps.average()) else "--"}")
            }
                tvHeater.text = buildString {
                    appendLine("Heater return: ${sig.heaterReturnTempC?.let { "%.1f C".format(it) } ?: "--"}")
                    appendLine("Heater flow  : ${sig.heaterFlowTempC?.let { "%.1f C".format(it) } ?: "--"}")
                    appendLine("Heater curr  : ${sig.heaterCurrent?.let { "%.1f A".format(it) } ?: "--"}")
                    appendLine("Heater power : ${sig.heaterPower?.let { "%.0f W".format(it) } ?: "--"}")
                    appendLine("AC current   : ${sig.acCurrent?.let { "%.1f A".format(it) } ?: "--"}")
                    appendLine("AC power     : ${sig.acPower?.let { "%.0f W".format(it) } ?: "--"}")
                }
                tvVehicle.text = buildString {
                    appendLine("VIN          : ${sig.vin ?: "--"}")
                    appendLine("Odometer     : ${sig.odometerMiles ?: "--"} mi (raw ${sig.odometerRaw ?: "--"})")
                    appendLine("Gear         : ${sig.gear ?: "--"}")
                    appendLine("RPM          : ${sig.motorRpm ?: "--"}")
                    appendLine("Ah           : ${sig.ampHours?.let { "%.1f".format(it) } ?: "--"}")
                    appendLine("Charging     : ${sig.chargingFlag ?: "--"}")
                    appendLine("Pack         : ${if (sig.is96CellPack == true) "96-cell" else "24-cell"}")
                    appendLine("Cells known  : ${sig.cellVoltages.count { it != null }}")
                }
                sig.soc?.let { socHistory.add(it.toFloat()); if (socHistory.size > maxHistory) socHistory.removeAt(0); gaugeSoc.value = it.toFloat(); cockpitView.soc = it.toFloat() }
                sig.motorRpm?.let { gaugeRpm.value = it.toFloat(); cockpitView.speedKmh = (it / 10f) }
                sig.packPower?.let { gaugePower.value = it.toFloat(); cockpitView.powerKw = (it / 1000.0).toFloat() }
                sig.gear?.let { cockpitView.gear = it.toString() }
                sig.chargingFlag?.let { cockpitView.charging = it }
                sig.odometerMiles?.let { cockpitView.rangeKm = (it * 1.60934 * 0.15).toFloat() }
                cockpitView.ready = true
                cockpitView.invalidate()
                try { historyDb.insert(sig.soc, sig.packVoltage, sig.packCurrent, sig.motorRpm, sig.gear?.toString(), sig.odometerMiles?.toDouble()) } catch (_: Exception) {}
                sig.packCurrent?.let { gaugeCurrent.value = it.toFloat() }
                sig.auxBatteryVoltage?.let { gaugeAux.value = it.toFloat() }
                sig.acPower?.let { gaugeAcPower.value = kotlin.math.abs(it).toFloat() }
                sig.packVoltage?.let { voltHistory.add(it.toFloat()); if (voltHistory.size > maxHistory) voltHistory.removeAt(0) }
                sig.packCurrent?.let { currHistory.add(it.toFloat()); if (currHistory.size > maxHistory) currHistory.removeAt(0) }
                val cells = sig.cellVoltages.filterNotNull()
                if (cells.size >= 2) {
                    val spread = (cells.maxOrNull()!! - cells.minOrNull()!!).toFloat()
                    cellSpreadHistory.add(spread); if (cellSpreadHistory.size > maxHistory) cellSpreadHistory.removeAt(0)
                }
                chartSoc.setData(ArrayList(socHistory), "SOC")
                chartVolt.setData(ArrayList(voltHistory), "V")
                chartCurr.setData(ArrayList(currHistory), "A")
                chartCellSpread.setData(ArrayList(cellSpreadHistory), "spread")
                sendToVeilService(sig, parser.getCurrentErrors())
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "onFrame error", e)
                }
            }
        }
        getSharedPreferences("veil_prefs", MODE_PRIVATE).edit().putString("last_device", device.address).apply()
        status.text = "Connecting to ${device.name}…"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                c.start()
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    status.text = "Connection failed: ${e.message}"
                    onErrorToast(e)
                }
            }
        }
    }

    private fun onErrorToast(e: Exception) {
        android.widget.Toast.makeText(this, "BT error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun sendToVeilService(signals: ZevSignals, errors: List<CanBusError>) {
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("veil_send", true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val host = prefs.getString("veil_host", "10.0.2.2")
                val port = prefs.getString("veil_port", "8100")
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


    private fun sendAppError(tag: String, message: String, stackTrace: String? = null) {
        val prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("veil_send", true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val host = prefs.getString("veil_host", "10.0.2.2")
                val port = prefs.getString("veil_port", "8100")
                val vehicleId = prefs.getString("vehicle_id", "VEHICLE_001")
                val url = "http://$host:$port/api/vehicles/$vehicleId/app-errors"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val escapedStack: String? = null  // stack trace sent separately via CrashLogger file
                val json = "{\"timestamp\":" + System.currentTimeMillis() + ",\"tag\":\"" + tag + "\",\"message\":\"" + message + "\",\"stackTrace\":null}"
                connection.outputStream.use { it.write(json.toByteArray()) }
                connection.responseCode
            } catch (_: Exception) {}
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
        R.id.action_history -> {
            val rows = historyDb.recent(50)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("History (last ${rows.size})")
                .setMessage(rows.joinToString("\n").ifEmpty { "No samples yet" })
                .setPositiveButton("OK", null)
                .show()
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

    private fun loadDemoData() {
        // Synthetic i-MiEV-like data for UI validation when no adapter is connected.
        val sig = org.caniorebirth.zev.ZevSignals(
            motorRpm = 2500,
            packCurrent = -12.5,
            packVoltage = 330.0,
            packPower = -4125.0,
            soc = 72.0,
            auxBatteryVoltage = 12.6,
            auxBatteryCurrent = 1.2,
            heaterReturnTempC = 22.0,
            heaterFlowTempC = 28.0,
            heaterCurrent = 5.0,
            acCurrent = 2.0,
            acPower = 660.0,
            heaterPower = 1650.0,
            odometerMiles = 45230,
            odometerRaw = 2612000,
            gear = 'D',
            chargingFlag = false,
            chargePowerFactor = 0,
            ampHours = 38.5,
            vin = "JF1AA5A50A0000001",
            cellVoltages = List(96) { 3.6 + (it % 7) * 0.02 },
            cellTemperatures = List(96) { 20 + (it % 5) },
            is96CellPack = true,
            canBusErrors = emptyList(),
            hasCanBusErrors = false
        )
        // Feed parser with a synthetic frame so state is consistent (optional).
        // Update UI directly.
        runOnUiThread {
            readout.text = describe(sig)
            updateErrorsView(emptyList())
            cellGrid.setVoltages(sig.cellVoltages)
            cellGrid.setTemperatures(sig.cellTemperatures.map { it?.toDouble() })
            cellTempGrid.setVoltages(sig.cellTemperatures.map { it?.toDouble() })
            tvHeater.text = buildString {
                appendLine("Heater return: ${sig.heaterReturnTempC?.let { "%.1f C".format(it) } ?: "--"}")
                appendLine("Heater flow  : ${sig.heaterFlowTempC?.let { "%.1f C".format(it) } ?: "--"}")
                appendLine("Heater curr  : ${sig.heaterCurrent?.let { "%.1f A".format(it) } ?: "--"}")
                appendLine("Heater power : ${sig.heaterPower?.let { "%.0f W".format(it) } ?: "--"}")
                appendLine("AC current   : ${sig.acCurrent?.let { "%.1f A".format(it) } ?: "--"}")
                appendLine("AC power     : ${sig.acPower?.let { "%.0f W".format(it) } ?: "--"}")
            }
            tvVehicle.text = buildString {
                appendLine("VIN          : ${sig.vin ?: "--"}")
                appendLine("Odometer     : ${sig.odometerMiles ?: "--"} mi")
                appendLine("Gear         : ${sig.gear ?: "--"}")
                appendLine("RPM          : ${sig.motorRpm ?: "--"}")
                appendLine("Ah           : ${sig.ampHours?.let { "%.1f".format(it) } ?: "--"}")
                appendLine("Charging     : ${sig.chargingFlag ?: "--"}")
                appendLine("Pack         : ${if (sig.is96CellPack == true) "96-cell" else "24-cell"}")
                appendLine("Cells known  : ${sig.cellVoltages.count { it != null }}")
                appendLine("Cockpit flags: --")
            }
            val cells = sig.cellVoltages.filterNotNull()
            val temps = sig.cellTemperatures.filterNotNull()
            tvCellSummary.text = buildString {
                append("Cells V: ")
                append("min ${cells.minOrNull()?.let { "%.3f".format(it) } ?: "--"}  ")
                append("max ${cells.maxOrNull()?.let { "%.3f".format(it) } ?: "--"}  ")
                append("avg ${if (cells.isNotEmpty()) "%.3f".format(cells.average()) else "--"}")
                append("   |   T: ")
                append("min ${temps.minOrNull()?.let { "%.0f".format(it) } ?: "--"}  ")
                append("max ${temps.maxOrNull()?.let { "%.0f".format(it) } ?: "--"}  ")
                append("avg ${if (temps.isNotEmpty()) "%.0f".format(temps.average()) else "--"}")
            }
            sig.soc?.let { socHistory.add(it.toFloat()); if (socHistory.size > maxHistory) socHistory.removeAt(0); gaugeSoc.value = it.toFloat(); cockpitView.soc = it.toFloat() }
            sig.motorRpm?.let { gaugeRpm.value = it.toFloat(); cockpitView.speedKmh = (it / 10f) }
            sig.packPower?.let { gaugePower.value = it.toFloat(); cockpitView.powerKw = (it / 1000.0).toFloat() }
            sig.gear?.let { cockpitView.gear = it.toString() }
            sig.chargingFlag?.let { cockpitView.charging = it }
            sig.odometerMiles?.let { cockpitView.rangeKm = (it * 1.60934 * 0.15).toFloat() }
            cockpitView.ready = true
            cockpitView.invalidate()
            sig.packCurrent?.let { gaugeCurrent.value = it.toFloat() }
            sig.auxBatteryVoltage?.let { gaugeAux.value = it.toFloat() }
            sig.acPower?.let { gaugeAcPower.value = kotlin.math.abs(it).toFloat() }
            for (i in 0 until 60) {
                socHistory.add((70 + (i % 5)).toFloat()); if (socHistory.size > maxHistory) socHistory.removeAt(0)
                voltHistory.add((330 + (i % 3)).toFloat()); if (voltHistory.size > maxHistory) voltHistory.removeAt(0)
                currHistory.add((-10 + (i % 7)).toFloat()); if (currHistory.size > maxHistory) currHistory.removeAt(0)
                cellSpreadHistory.add((0.1f + (i % 3) * 0.02f)); if (cellSpreadHistory.size > maxHistory) cellSpreadHistory.removeAt(0)
            }
            chartSoc.setData(ArrayList(socHistory), "SOC")
            chartVolt.setData(ArrayList(voltHistory), "V")
            chartCurr.setData(ArrayList(currHistory), "A")
            chartCellSpread.setData(ArrayList(cellSpreadHistory), "spread")
            sig.gear?.let { tvGear.text = it.toString() }
            sig.odometerMiles?.let { tvRange.text = "%.0f km".format(it * 1.60934) }
            status.text = "Demo mode"
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
