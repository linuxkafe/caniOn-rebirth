package org.caniorebirth.leaf

/**
 * The signals this client extracts from the Leaf CAN bus.
 *
 * Every field is nullable: a signal is only present once a frame carrying it
 * has been seen. Units are SI unless stated otherwise.
 */
data class LeafSignals(
    // --- 0x298 ---------------------------------------------------------
    val motorRpm: Int? = null,

    // --- 0x373 ---------------------------------------------------------
    /** Pack current in amperes. Positive = discharge, negative = charge. */
    val packCurrent: Double? = null,
    /** Pack voltage in volts. */
    val packVoltage: Double? = null,
    /** Pack power in watts. `packCurrent * packVoltage`. */
    val packPower: Double? = null,

    // --- 0x374 ---------------------------------------------------------
    /** State of charge in percent. */
    val soc: Double? = null,

    // --- 0x389 ---------------------------------------------------------
    val auxBatteryVoltage: Double? = null,
    val auxBatteryCurrent: Double? = null,

    // --- 0x384 ---------------------------------------------------------
    /** Heater ( PTC ) return temperature in °C. */
    val heaterReturnTempC: Double? = null,
    /** Heater flow temperature in °C. */
    val heaterFlowTempC: Double? = null,
    /** Heater current in amperes. */
    val heaterCurrent: Double? = null,
    /** Air-conditioning current in amperes. */
    val acCurrent: Double? = null,
    /** Air-conditioning power in watts. */
    val acPower: Double? = null,
    /** Heater power in watts. */
    val heaterPower: Double? = null,

    // --- 0x412 ---------------------------------------------------------
    /** Odometer in miles. */
    val odometerMiles: Int? = null,
    /** Raw odometer counter, before division. */
    val odometerRaw: Int? = null,

    // --- 0x418 ---------------------------------------------------------
    /** Gear selector: P, R, N, D, B or C. */
    val gear: Char? = null,

    // --- 0x346 ---------------------------------------------------------
    val chargingFlag: Boolean? = null,

    // --- 0x210 ---------------------------------------------------------
    /** Charge power factor, 0.4 steps. */
    val chargePowerFactor: Int? = null,

    // --- 0x762 ---------------------------------------------------------
    /** Amp-hours in the pack. */
    val ampHours: Double? = null,

    // --- 0x29A / 0x6FA -------------------------------------------------
    /** Vehicle identification number, once 17 characters have been collected. */
    val vin: String? = null,

    // --- 0x6E1 .. 0x6E4 ------------------------------------------------
    /** Cell voltages in volts, indexed 0..95. Null where not yet known. */
    val cellVoltages: List<Double?> = emptyList(),
    /** Cell temperatures in °C, indexed 0..95. Null where not yet known. */
    val cellTemperatures: List<Int?> = emptyList(),
    /** True once the pack has been identified as 96-cell rather than 24-cell. */
    val is96CellPack: Boolean? = null,
)
