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

        val etVeilHost = findViewById<EditText>(R.id.etVeilHost)
        val etVeilPort = findViewById<EditText>(R.id.etVeilPort)
        val etVehicleId = findViewById<EditText>(R.id.etVehicleId)
        val swFahrenheit = findViewById<Switch>(R.id.swFahrenheit)
        val swKilometers = findViewById<Switch>(R.id.swKilometers)
        val swDarkMode = findViewById<Switch>(R.id.swDarkMode)
        val swForceLandscape = findViewById<Switch>(R.id.swForceLandscape)
        val swVeilSend = findViewById<Switch>(R.id.swVeilSend)
        val swFileLog = findViewById<Switch>(R.id.swFileLog)
        val btnForgetDevice = findViewById<Button>(R.id.btnForgetDevice)
        val btnVeilidTest = findViewById<Button>(R.id.btnVeilidTest)
        val etOdometerDivisor = findViewById<EditText>(R.id.etOdometerDivisor)
        val sw96Cell = findViewById<Switch>(R.id.sw96Cell)
        val btnSave = findViewById<Button>(R.id.btnSave)

        etVeilHost.setText(prefs.getString("veil_host", "10.0.2.2"))
        etVeilPort.setText(prefs.getString("veil_port", "8100"))
        etVehicleId.setText(prefs.getString("vehicle_id", "VEHICLE_001"))
        swFahrenheit.isChecked = prefs.getBoolean("use_fahrenheit", false)
        swKilometers.isChecked = prefs.getBoolean("use_kilometers", false)
        swDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        swForceLandscape.isChecked = prefs.getBoolean("force_landscape", false)
        swVeilSend.isChecked = prefs.getBoolean("veil_send", true)
        swFileLog.isChecked = prefs.getBoolean("file_log", false)
        etOdometerDivisor.setText(prefs.getString("odometer_divisor", "57.75"))
        sw96Cell.isChecked = prefs.getBoolean("force_96cell", false)

        btnForgetDevice.setOnClickListener {
            prefs.edit().remove("last_device").apply()
            Toast.makeText(this, "Device forgotten", Toast.LENGTH_SHORT).show()
        }

        btnVeilidTest.setOnClickListener {
            val storageDir = filesDir.absolutePath
            val result = VeilidBridge.veilidInit("canion", "rebirth", storageDir)
            val attachResult = if (result == 0) VeilidBridge.veilidAttach() else -1
            val version = VeilidBridge.veilidVersion()
            val state = VeilidBridge.veilidGetState()
            Toast.makeText(this, "Veilid init: $result, attach: $attachResult, version: $version, state: $state", Toast.LENGTH_LONG).show()
        }

        btnSave.setOnClickListener {
            prefs.edit()
                .putString("veil_host", etVeilHost.text.toString().trim())
                .putString("veil_port", etVeilPort.text.toString().trim())
                .putString("vehicle_id", etVehicleId.text.toString().trim())
                .putBoolean("use_fahrenheit", swFahrenheit.isChecked)
                .putBoolean("use_kilometers", swKilometers.isChecked)
                .putBoolean("dark_mode", swDarkMode.isChecked)
                .putBoolean("force_landscape", swForceLandscape.isChecked)
                .putBoolean("veil_send", swVeilSend.isChecked)
                .putBoolean("file_log", swFileLog.isChecked)
                .putString("odometer_divisor", etOdometerDivisor.text.toString().trim())
                .putBoolean("force_96cell", sw96Cell.isChecked)
                .apply()
            applyNightMode(swDarkMode.isChecked)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
