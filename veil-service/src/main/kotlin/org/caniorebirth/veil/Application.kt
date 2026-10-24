package org.caniorebirth.veil

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

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
data class AppError(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val stackTrace: String? = null,
)

@Serializable
data class VehicleState(
    val vehicleId: String,
    val lastUpdate: Long,
    val signals: ZevSignals = ZevSignals(),
    val errors: List<CanBusError> = emptyList(),
    val appErrors: List<AppError> = emptyList(),
)

@Serializable
data class SignalsPayload(
    val signals: ZevSignals,
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
            VehicleState(vehicleId = it, lastUpdate = System.currentTimeMillis())
        }
    }

    fun restoreVehicle(vehicleId: String, state: VehicleState) {
        vehicles[vehicleId] = state
    }

    fun getVehicle(vehicleId: String): VehicleState? = vehicles[vehicleId]

    fun updateSignals(vehicleId: String, signals: ZevSignals, errors: List<CanBusError>) {
        val state = vehicles.computeIfPresent(vehicleId) { _, st ->
            st.copy(lastUpdate = System.currentTimeMillis(), signals = signals, errors = errors)
        } ?: VehicleState(vehicleId = vehicleId, lastUpdate = System.currentTimeMillis(), signals = signals, errors = errors)
        vehicles[vehicleId] = state
        try { VehicleStore.save(vehicleId, state) } catch (_: Exception) {}
    }

    fun clearErrors(vehicleId: String): Boolean {
        val result = vehicles.computeIfPresent(vehicleId) { _, state ->
            state.copy(errors = emptyList())
        } != null
        vehicles[vehicleId]?.let { try { VehicleStore.save(vehicleId, it) } catch (_: Exception) {} }
        return result
    }

    fun addAppError(vehicleId: String, error: AppError) {
        val state = vehicles.computeIfPresent(vehicleId) { _, st ->
            st.copy(appErrors = st.appErrors + error)
        } ?: VehicleState(vehicleId = vehicleId, lastUpdate = System.currentTimeMillis(), appErrors = listOf(error))
        vehicles[vehicleId] = state
        try { VehicleStore.save(vehicleId, state) } catch (_: Exception) {}
    }

    fun getAppErrors(vehicleId: String): List<AppError> = vehicles[vehicleId]?.appErrors ?: emptyList()

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
        get("/health") {
            call.respondText("OK")
        }

        post("/api/vehicles/register") {
            val vehicleId = call.receiveText()
            val vehicle = VehicleRegistry.registerVehicle(vehicleId)
            call.respond(vehicle)
        }

        get("/api/vehicles/{vehicleId}") {
            val vehicleId = call.parameters["vehicleId"]!!
            val vehicle = VehicleRegistry.getVehicle(vehicleId)
            if (vehicle != null) call.respond(vehicle)
            else call.respondText("Vehicle not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }

        get("/api/vehicles") {
            call.respond(VehicleRegistry.getAllVehicles())
        }

        post("/api/vehicles/{vehicleId}/signals") {
            val vehicleId = call.parameters["vehicleId"]!!
            val payload = call.receive<SignalsPayload>()
            VehicleRegistry.updateSignals(vehicleId, payload.signals, payload.errors)
            call.respond(ErrorResponse(true, "Signals updated"))
        }

        post("/api/vehicles/{vehicleId}/errors/clear") {
            val vehicleId = call.parameters["vehicleId"]!!
            val success = VehicleRegistry.clearErrors(vehicleId)
            call.respond(ErrorResponse(success, if (success) "Errors cleared" else "Vehicle not found"))
        }

        get("/api/vehicles/{vehicleId}/errors") {
            val vehicleId = call.parameters["vehicleId"]!!
            val vehicle = VehicleRegistry.getVehicle(vehicleId)
            if (vehicle != null) call.respond(vehicle.errors)
            else call.respondText("Vehicle not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }

        // App error capture
        post("/api/vehicles/{vehicleId}/app-errors") {
            val vehicleId = call.parameters["vehicleId"]!!
            val error = call.receive<AppError>()
            VehicleRegistry.addAppError(vehicleId, error)
            call.respond(ErrorResponse(true, "App error recorded"))
        }

        get("/api/vehicles/{vehicleId}/app-errors") {
            val vehicleId = call.parameters["vehicleId"]!!
            call.respond(VehicleRegistry.getAppErrors(vehicleId))
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8100, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}
