package org.caniorebirth.android

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

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
        val btnSave = findViewById<Button>(R.id.btnSave)

        etVeilHost.setText(prefs.getString("veil_host", "10.0.2.2"))
        etVeilPort.setText(prefs.getString("veil_port", "8080"))
        etVehicleId.setText(prefs.getString("vehicle_id", "VEHICLE_001"))
        swFahrenheit.isChecked = prefs.getBoolean("use_fahrenheit", false)
        swKilometers.isChecked = prefs.getBoolean("use_kilometers", false)

        btnSave.setOnClickListener {
            prefs.edit()
                .putString("veil_host", etVeilHost.text.toString().trim())
                .putString("veil_port", etVeilPort.text.toString().trim())
                .putString("vehicle_id", etVehicleId.text.toString().trim())
                .putBoolean("use_fahrenheit", swFahrenheit.isChecked)
                .putBoolean("use_kilometers", swKilometers.isChecked)
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
