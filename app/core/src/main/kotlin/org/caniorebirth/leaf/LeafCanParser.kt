package org.caniorebirth.leaf

/**
 * Decodes Nissan Leaf CAN frames into [LeafSignals].
 *
 * The parser is a faithful re-implementation of the behaviour observed in the
 * caniOn v152 client (see `docs/PROTOCOL.md`). It is stateless with respect
 * to the transport: feed it [CanFrame]s and read the accumulated signals.
 *
 * ### Identifier map
 *
 * | ID    | Signal                          |
 * |-------|---------------------------------|
 * | 0x210 | charge power factor (0.4 steps)  |
 * | 0x298 | motor RPM                        |
 * | 0x29A | VIN fragment (part 2)            |
 * | 0x346 | charging flag                    |
 * | 0x373 | pack current / voltage           |
 * | 0x374 | state of charge                  |
 * | 0x384 | heater / AC temperatures         |
 * | 0x389 | auxiliary battery                |
 * | 0x412 | odometer                         |
 * | 0x418 | gear selector                    |
 * | 0x424 | cockpit lights                   |
 * | 0x6E1 | cell block 1                     |
 * | 0x6E2 | cell block 2                     |
 * | 0x6E3 | cell block 3                     |
 * | 0x6E4 | cell block 4                     |
 * | 0x6FA | VIN fragment (part 1)            |
 * | 0x762 | amp-hours                        |
 */
class LeafCanParser(
    /** Odometer divisor. The observed default for the Leaf is 57.75. */
    private val odometerDivisor: Double = DEFAULT_ODOMETER_DIVISOR,
    /** True when the vehicle reports temperatures in °F. */
    private val useFahrenheit: Boolean = false,
) {

    // ---- working state -------------------------------------------------

    private var motorRpm: Int? = null
    private var packCurrent: Double? = null
    private var packVoltage: Double? = null
    private var packPower: Double? = null
    private var soc: Double? = null
    private var auxVoltage: Double? = null
    private var auxCurrent: Double? = null
    private var heaterReturnTemp: Double? = null
    private var heaterFlowTemp: Double? = null
    private var heaterCurrent: Double? = null
    private var acCurrent: Double? = null
    private var acPower: Double? = null
    private var heaterPower: Double? = null
    private var odometerRaw: Int? = null
    private var odometerMiles: Int? = null
    private var gear: Char? = null
    private var charging: Boolean? = null
    private var chargePowerFactor: Int? = null
    private var ampHours: Double? = null

    // Cell data. Up to 96 cells in 12 groups of 8.
    private val cellVoltage = DoubleArray(CELL_COUNT)
    private val cellTemperature = IntArray(CELL_COUNT)
    private val cellVoltageKnown = BooleanArray(CELL_COUNT)
    private val cellTemperatureKnown = BooleanArray(CELL_COUNT)

    private var is96CellPack: Boolean? = null
    private var packSizeDecided = false

    // VIN assembly
    private val vinParts = arrayOfNulls<String>(3)
    private var vin: String? = null

    /** Number of cells the pack was identified as having, once known. */
    var detectedCellCount: Int? = null
        private set

    // ---- public API ----------------------------------------------------

    /** Feed one frame. Returns the signals as they stand after the update. */
    fun accept(frame: CanFrame): LeafSignals {
        when (frame.id) {
            ID_CHARGE_POWER_FACTOR -> if (frame.size == SHORT) {
                chargePowerFactor = (frame.u(2) * 0.4).toInt()
            }

            ID_GEAR -> if (frame.size == SHORT) {
                gear = gearFor(frame.u(0))
            }

            ID_MOTOR_RPM -> {
                var rpm = frame.u16(6) - 10000
                if (rpm < 16 && rpm > -16) rpm = 0
                motorRpm = rpm
            }

            ID_VIN_PART_2 -> updateVin(2, frame)

            ID_VIN_PART_1 -> updateVin(1, frame)

            ID_CHARGING -> {
                charging = (frame.u(4) and CHARGING_MASK) != 0
            }

            ID_PACK_CURRENT -> {
                val amp = (frame.u16(2) - PACK_CURRENT_OFFSET) / 100.0
                val volt = frame.u16(4) / 10.0
                packCurrent = amp
                packVoltage = volt
                packPower = amp * volt
            }

            ID_SOC -> soc = (frame.u(1) - 10) / 2.0

            ID_HEATER_AC -> {
                heaterReturnTemp = temperature(frame.u(5))
                heaterFlowTemp = temperature(frame.u(6))
                heaterCurrent = -frame.u(4) / 10.0
                val ac = if (frame.u(0) == 0xFF && frame.u(1) == 0xFF) {
                    0.0
                } else {
                    -(frame.u16(0)) / 1000.0
                }
                acCurrent = ac
                acPower = ac * (packVoltage ?: 0.0)
                heaterPower = (heaterCurrent ?: 0.0) * (packVoltage ?: 0.0)
            }

            ID_AUX_BATTERY -> {
                auxCurrent = frame.u(6) / 10.0
                auxVoltage = frame.u(1).toDouble()
            }

            ID_ODOMETER -> {
                val raw = frame.u24(2)
                odometerRaw = raw
                odometerMiles = ((raw / odometerDivisor) * 1000.0).toInt()
            }

            ID_CELL_BLOCK_1 -> updateCells(1, frame)
            ID_CELL_BLOCK_2 -> updateCells(2, frame)
            ID_CELL_BLOCK_3 -> updateCells(3, frame)
            ID_CELL_BLOCK_4 -> updateCells(4, frame)

            ID_AMP_HOURS -> if (frame.u(0) == AMP_HOURS_MARKER) {
                ampHours = frame.u16(3) / 10.0
            }
        }
        return signals()
    }

    /** Current state of every signal. */
    fun signals(): LeafSignals = LeafSignals(
        motorRpm = motorRpm,
        packCurrent = packCurrent,
        packVoltage = packVoltage,
        packPower = packPower,
        soc = soc,
        auxBatteryVoltage = auxVoltage,
        auxBatteryCurrent = auxCurrent,
        heaterReturnTempC = heaterReturnTemp,
        heaterFlowTempC = heaterFlowTemp,
        heaterCurrent = heaterCurrent,
        acCurrent = acCurrent,
        acPower = acPower,
        heaterPower = heaterPower,
        odometerMiles = odometerMiles,
        odometerRaw = odometerRaw,
        gear = gear,
        chargingFlag = charging,
        chargePowerFactor = chargePowerFactor,
        ampHours = ampHours,
        vin = vin,
        cellVoltages = cellVoltages(),
        cellTemperatures = cellTemperatures(),
        is96CellPack = is96CellPack,
    )

    /**
     * Cell voltages that have been decoded, in volts.
     *
     * A cell that has not been reported yet is `null`, not `0.0` — a zero
     * reading is indistinguishable from a real (but dead) cell otherwise.
     */
    fun cellVoltages(): List<Double?> = cellVoltage.mapIndexed { i, v ->
        if (cellVoltageKnown[i]) v else null
    }

    /**
     * Cell temperatures that have been decoded, in °C.
     *
     * A cell that has not been reported yet is `null`.
     */
    fun cellTemperatures(): List<Int?> = cellTemperature.mapIndexed { i, t ->
        if (cellTemperatureKnown[i]) t else null
    }

    // ---- internals -----------------------------------------------------

    private fun temperature(raw: Int): Double = if (useFahrenheit) {
        ((raw - 32) / 1.8) - 3.0
    } else {
        (raw * 0.6) - 40.0
    }

    /**
     * The gear byte is matched by exact value, as observed. The set of values
     * carries more than one status bit, so a masked comparison does not
     * reproduce the original behaviour.
     */
    private fun gearFor(byte: Int): Char? = when (byte) {
        0 -> 'P'
        82 -> 'R'
        78 -> 'N'
        68 -> 'D'
        131 -> 'B'
        50 -> 'C'
        else -> null
    }

    private fun updateVin(partIndex: Int, frame: CanFrame) {
        val kind = frame.u(0)
        if (kind !in 0..2) return
        val length = if (kind == 2) 3 else 7
        val text = buildString {
            for (i in 0 until length) {
                val c = frame.u(1 + i)
                if (c != 0) append(c.toChar())
            }
        }
        vinParts[kind] = text
        val joined = vinParts.filterNotNull().joinToString("")
        if (joined.length == VIN_LENGTH) {
            vin = joined
        }
    }

    private fun updateCells(block: Int, frame: CanFrame) {
        var group = frame.u(0)
        if (group in 0x41..0x4C) group -= 64
        if (group < 1 || group > 12) return // "bad frame"

        // A 24-cell pack only ever reports groups 1..4. Seeing a group above
        // 4 is the simplest reliable indication of a 96-cell pack.
        //
        // The original client instead counted valid readings and compared the
        // total against the pack voltage. That heuristic is fragile: it needs
        // a complete sweep of all 12 groups before it can decide, and it
        // mis-classifies a pack whose cells read close to zero. The group
        // index is unambiguous, so this implementation uses it.
        if (group > 4) {
            is96CellPack = true
            detectedCellCount = 96
        } else if (is96CellPack == null) {
            is96CellPack = false
            detectedCellCount = 24
        }

        val first = ((group - 1) * 8) + ((block - 1) * 2)
        if (first < 0 || first + 1 >= CELL_COUNT) return

        for (offset in 0..1) {
            val index = first + offset
            val hi = frame.u(4 + offset * 2)
            val lo = frame.u(5 + offset * 2)
            var voltage = ((hi shl 8) or lo) / 200.0
            if (voltage > 0.0) voltage += 2.1
            cellVoltage[index] = voltage
            cellVoltageKnown[index] = true

            val tempByte = if (block == 1) frame.u(2 + offset) else frame.u(1 + offset)
            cellTemperature[index] = if (tempByte > 0) tempByte - 50 else tempByte
            cellTemperatureKnown[index] = true
        }

        if (is96CellPack == true && (group == 6 || group == 12)) {
            cellVoltage[first] = 0.0
            cellVoltage[first + 1] = 0.0
            cellTemperature[first] = -50
            cellTemperature[first + 1] = -50
        }
    }

    companion object {
        const val DEFAULT_ODOMETER_DIVISOR: Double = 57.75

        const val CELL_COUNT = 96
        const val VIN_LENGTH = 17

        private const val SHORT = 7
        private const val CHARGING_MASK = 0x20
        private const val PACK_CURRENT_OFFSET = 0
        private const val AMP_HOURS_MARKER = 36

        const val ID_CHARGE_POWER_FACTOR = 0x210
        const val ID_MOTOR_RPM = 0x298
        const val ID_VIN_PART_2 = 0x29A
        const val ID_CHARGING = 0x346
        const val ID_PACK_CURRENT = 0x373
        const val ID_SOC = 0x374
        const val ID_HEATER_AC = 0x384
        const val ID_AUX_BATTERY = 0x389
        const val ID_ODOMETER = 0x412
        const val ID_GEAR = 0x418
        const val ID_CELL_BLOCK_1 = 0x6E1
        const val ID_CELL_BLOCK_2 = 0x6E2
        const val ID_CELL_BLOCK_3 = 0x6E3
        const val ID_CELL_BLOCK_4 = 0x6E4
        const val ID_VIN_PART_1 = 0x6FA
        const val ID_AMP_HOURS = 0x762
    }
}
