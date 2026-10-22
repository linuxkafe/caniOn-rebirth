package org.caniorebirth.veil

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toEpochMilliseconds
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class VehicleData(
    val vehicleId: String,
    val timestamp: Long,
    val signals: VehicleSignals,
    val errors: List<CanBusError>
)

@Serializable
data class ZevSignals(
    val motorRpm: Int? = null,
    val packCurrent: Double? = null,
    val packVoltage: Double? = null,
    val packPower: Double? = null,
    val soc: Double? = null,
    val auxBatteryVoltage: Double? = null,
    val auxBatteryCurrent: Double? = null,
    val heaterReturnTempC: Double? = null,
    val heaterFlowTempC: Double? = null,
    val heaterCurrent: Double? = null,
    val acCurrent: Double? = null,
    val acPower: Double? = null,
    val heaterPower: Double? = null,
    val odometerMiles: Int? = null,
    val odometerRaw: Int? = null,
    val gear: Char? = null,
    val chargingFlag: Boolean? = null,
    val chargePowerFactor: Int? = null,
    val ampHours: Double? = null,
    val vin: String? = null,
    val cellVoltages: List<Double?> = emptyList(),
    val cellTemperatures: List<Int?> = emptyList(),
    val is96CellPack: Boolean? = null,
    val canBusErrors: List<CanBusError> = emptyList(),
    val hasCanBusErrors: Boolean = false,
)

@Serializable
data class CanBusError(
    val code: Int,
    val description: String,
    val timestamp: Long,
    val cleared: Boolean = false,
    val clearedTimestamp: Long? = null,
)

@Serializable
data class VehicleSignals(
    val motorRpm: Int? = null,
    val packCurrent: Double? = null,
    val packVoltage: Double? = null,
    val packPower: Double? = null,
    val soc: Double? = null,
    val auxBatteryVoltage: Double? = null,
    val auxBatteryCurrent: Double? = null,
    val heaterReturnTempC: Double? = null,
    val heaterFlowTempC: Double? = null,
    val heaterCurrent: Double? = null,
    val acCurrent: Double? = null,
    val acPower: Double? = null,
    val heaterPower: Double? = null,
    val odometerMiles: Int? = null,
    val odometerRaw: Int? = null,
    val gear: Char? = null,
    val chargingFlag: Boolean? = null,
    val chargePowerFactor: Int? = null,
    val ampHours: Double? = null,
    val vin: String? = null,
    val cellVoltages: List<Double?> = emptyList(),
    val cellTemperatures: List<Int?> = emptyList(),
    val is96CellPack: Boolean? = null,
    val canBusErrors: List<CanBusError> = emptyList(),
    val hasCanBusErrors: Boolean = false,
)

@Serializable
data class VehicleState(
    val vehicleId: String,
    val lastUpdate: Long,
    val signals: VehicleSignals,
    val errors: List<CanBusError> = emptyList(),
)

@Serializable
data class ErrorResponse(
    val success: Boolean,
    val message: String,
)

object VehicleRegistry {
    private val vehicles = ConcurrentHashMap<String, VehicleState>()
    
    fun registerVehicle(vehicleId: String): VehicleState {
        return vehicles.computeIfAbsent(vehicleId) { 
            VehicleState(
                vehicleId = it,
                lastUpdate = System.currentTimeMillis(),
                signals = ZevSignals(),
            )
        }
    }
    
    fun getVehicle(vehicleId: String): VehicleState? = vehicles[vehicleId]
    
    fun updateSignals(vehicleId: String, signals: ZevSignals, errors: List<CanBusError>) {
        vehicles.computeIfPresent(vehicleId) { _, state ->
            state.copy(
                lastUpdate = System.currentTimeMillis(),
                signals = it,
                errors = it
            )
        }
    }
    
    fun clearErrors(vehicleId: String): Boolean {
        return vehicles.computeIfPresent(vehicleId) { _, state ->
            state.copy(errors = emptyList())
        } != null
    }
    
    fun getAllVehicles(): List<VehicleState> = vehicles.values.toList()
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    
    install(CORS) {
        anyHost()
    }
    
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText("Internal server error: ${cause.message}", 
                status = io.ktor.http.HttpStatusCode.InternalServerError)
        }
    }
    
    routing {
        // Health check
        get("/health") {
            call.respondText("OK")
        }
        
        // Register a vehicle
        post("/api/vehicles/register") {
            val vehicleId = call.receiveText()
            val vehicle = VehicleRegistry.registerVehicle(vehicleId)
            call.respond(VehicleState(
                vehicleId = vehicle.vehicleId,
                lastUpdate = vehicle.lastUpdate,
                signals = vehicle.signals,
                errors = vehicle.errors
            ))
        }
        
        // Get vehicle state
        get("/api/vehicles/{vehicleId}") {
            val vehicleId = call.parameters["vehicleId"]!!
            val vehicle = VehicleRegistry.getVehicle(vehicleId)
            if (vehicle != null) {
                call.respond(vehicle)
            } else {
                call.respondText("Vehicle not found", status = io.ktor.http.HttpStatusCode.NotFound)
            }
        }
        
        // Get all vehicles
        get("/api/vehicles") {
            call.respond(VehicleRegistry.getAllVehicles())
        }
        
        // Update vehicle signals
        post("/api/vehicles/{vehicleId}/signals") {
            val vehicleId = call.parameters["vehicleId"]!!
            val payload = call.receive<ZevSignalsWithErrors>()
            VehicleRegistry.updateSignals(vehicleId, payload.signals, payload.errors)
            call.respond(ErrorResponse(true, "Signals updated"))
        }
        
        // Clear errors
        post("/api/vehicles/{vehicleId}/errors/clear") {
            val vehicleId = call.parameters["vehicleId"]!!
            val success = VehicleRegistry.clearErrors(vehicleId)
            call.respond(ErrorResponse(success, if (success) "Errors cleared" else "Vehicle not found"))
        }
        
        // Get errors
        get("/api/vehicles/{vehicleId}/errors") {
            val vehicleId = call.parameters["vehicleId"]!!
            val vehicle = VehicleRegistry.getVehicle(vehicleId)
            if (vehicle != null) {
                call.respond(vehicle.errors)
            } else {
                call.respondText("Vehicle not found", status = io.ktor.http.HttpStatusCode.NotFound)
            }
        }
    }
}

@Serializable
data class ZevSignalsWithErrors(
    val signals: ZevSignals,
    val errors: List<CanBusError> = emptyList(),
)

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = ::module)
        .start(wait = true)
}