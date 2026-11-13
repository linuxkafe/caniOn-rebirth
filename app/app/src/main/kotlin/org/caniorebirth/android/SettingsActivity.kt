package org.caniorebirth.android

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {
    private fun applyNightMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("veil_prefs", MODE_PRIVATE)

        val swFahrenheit = findViewById<Switch>(R.id.swFahrenheit)
        val swKilometers = findViewById<Switch>(R.id.swKilometers)
        val swDarkMode = findViewById<Switch>(R.id.swDarkMode)
        val swFullScreen = findViewById<Switch>(R.id.swFullScreen)
        val swVeilidEnabled = findViewById<Switch>(R.id.swVeilidEnabled)
        val swForceLandscape = findViewById<Switch>(R.id.swForceLandscape)
        val swVeilSend = findViewById<Switch>(R.id.swVeilSend)
        val swFileLog = findViewById<Switch>(R.id.swFileLog)
        val btnForgetDevice = findViewById<Button>(R.id.btnForgetDevice)
        val btnVeilidTest = findViewById<Button>(R.id.btnVeilidTest)
        val btnOpenLogDir = findViewById<Button>(R.id.btnOpenLogDir)
        val etOdometerDivisor = findViewById<EditText>(R.id.etOdometerDivisor)
        val etLogDir = findViewById<EditText>(R.id.etLogDir)
        val etVeilidRecordKey = findViewById<EditText>(R.id.etVeilidRecordKey)
        val sw96Cell = findViewById<Switch>(R.id.sw96Cell)
        val btnSave = findViewById<Button>(R.id.btnSave)

        swFahrenheit.isChecked = prefs.getBoolean("use_fahrenheit", false)
        swKilometers.isChecked = prefs.getBoolean("use_kilometers", false)
        swDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        swFullScreen.isChecked = prefs.getBoolean("full_screen", false)
        swVeilidEnabled.isChecked = prefs.getBoolean("veilid_enabled", false)
        swForceLandscape.isChecked = prefs.getBoolean("force_landscape", false)
        swVeilSend.isChecked = prefs.getBoolean("veil_send", true)
        swFileLog.isChecked = prefs.getBoolean("file_log", false)
        etOdometerDivisor.setText(prefs.getString("odometer_divisor", "57.75"))
        etLogDir.setText(prefs.getString("log_dir", ""))
        etVeilidRecordKey.setText(prefs.getString("veilid_record_key", ""))
        sw96Cell.isChecked = prefs.getBoolean("force_96cell", false)

        btnForgetDevice.setOnClickListener {
            prefs.edit().remove("last_device").apply()
            Toast.makeText(this, "Device forgotten", Toast.LENGTH_SHORT).show()
        }

        btnVeilidTest.setOnClickListener {
            val storageDir = filesDir.absolutePath
            val result = VeilidBridge.veilidInit("canion", "rebirth", storageDir)
            val attachResult = if (result == 0) VeilidBridge.veilidAttach() else -1
            Thread.sleep(2000)
            val version = VeilidBridge.veilidVersion()
            val state = VeilidBridge.veilidGetState()
            val key = if (state == 2) VeilidBridge.veilidDhtCreate(2) else null
            val setRes = if (key != null) VeilidBridge.veilidDhtSet(key, 0, "hello from caniOn") else -9
            val got = if (key != null) VeilidBridge.veilidDhtGet(key, 0) else null
            Toast.makeText(this, "init:$result attach:$attachResult state:$state ver:$version key:${key?.take(20)} set:$setRes get:$got", Toast.LENGTH_LONG).show()
        }

        btnOpenLogDir.setOnClickListener {
            val dir = CrashLogger.logDir(this)
            dir.mkdirs()
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.setDataAndType(android.net.Uri.fromFile(dir), "*/*")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent) } catch (e: Exception) {
                Toast.makeText(this, "No file manager available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        swVeilidEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Veilid telemetry")
                    .setMessage("Veilid is a peer-to-peer network. When enabled, this app publishes SOC, battery voltage, current, RPM, gear and error count to a DHT record that can be read from anywhere in the world without opening ports.\n\nHow to access the data:\n1. Note the record key shown in Settings (or in the toast after 'Test Veilid Init').\n2. On any device running a Veilid node, call get_dht_value(record_key, subkey) for subkeys 0..7.\n3. Subkey 0 = SOC, 1 = voltage, 2 = current, 3 = RPM, 4 = has errors, 5 = odometer, 6 = gear, 7 = error count.\n\nDisable at any time to keep all data local.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        btnSave.setOnClickListener {
            prefs.edit()
                .putBoolean("use_fahrenheit", swFahrenheit.isChecked)
                .putBoolean("use_kilometers", swKilometers.isChecked)
                .putBoolean("dark_mode", swDarkMode.isChecked)
                .putBoolean("full_screen", swFullScreen.isChecked)
                .putBoolean("veilid_enabled", swVeilidEnabled.isChecked)
                .putBoolean("force_landscape", swForceLandscape.isChecked)
                .putBoolean("veil_send", swVeilSend.isChecked)
                .putBoolean("file_log", swFileLog.isChecked)
                .putString("odometer_divisor", etOdometerDivisor.text.toString().trim())
                .putString("log_dir", etLogDir.text.toString().trim())
                .putString("veilid_record_key", etVeilidRecordKey.text.toString().trim())
                .putBoolean("force_96cell", sw96Cell.isChecked)
                .apply()
            applyNightMode(swDarkMode.isChecked)
            prefs.edit().putBoolean("full_screen", swFullScreen.isChecked).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
