package org.caniorebirth.veil

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object VehicleStore {
    private lateinit var conn: Connection
    private val json = Json { ignoreUnknownKeys = true }

    fun init(dbPath: String = "veil.db") {
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS vehicles (
                vehicle_id TEXT PRIMARY KEY,
                data TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        // Load existing vehicles
        val rs = conn.createStatement().executeQuery("SELECT vehicle_id, data FROM vehicles")
        while (rs.next()) {
            val id = rs.getString("vehicle_id")
            val data = rs.getString("data")
            try {
                val state = json.decodeFromString<VehicleState>(data)
                VehicleRegistry.restoreVehicle(id, state)
            } catch (_: Exception) {}
        }
        rs.close()
    }

    fun save(vehicleId: String, state: VehicleState) {
        val data = json.encodeToString(state)
        val stmt = conn.prepareStatement(
            "INSERT OR REPLACE INTO vehicles (vehicle_id, data, updated_at) VALUES (?, ?, ?)"
        )
        stmt.setString(1, vehicleId)
        stmt.setString(2, data)
        stmt.setLong(3, System.currentTimeMillis())
        stmt.executeUpdate()
        stmt.close()
    }

    fun close() {
        if (::conn.isInitialized) conn.close()
    }
}
