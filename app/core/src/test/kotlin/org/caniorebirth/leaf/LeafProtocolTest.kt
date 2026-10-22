package org.caniorebirth.leaf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the frame splitter and the Leaf signal decoder.
 *
 * The fixtures are synthetic frames in the exact wire layout an ELM327
 * produces with `ATH1` / `ATS0`, matching the layout the original caniOn
 * client consumed.
 *
 * Frame layout reminder:
 *  - 3 hex characters: CAN identifier
 *  - 2 hex characters per data byte
 *  - 17 characters total = 7 data bytes
 *  - 19 characters total = 8 data bytes
 */
class LeafProtocolTest {

    // ---- FrameSplitter -------------------------------------------------

    @Test
    fun `splits a 17 character frame into 7 data bytes`() {
        val splitter = FrameSplitter()
        // "6FA" + 7 bytes (0x01 0x10 0x00 0x00 0x00 0x00 0x00)
        val frames = splitter.feed("6FA01100000000000\r".toByteArray())
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(0x6FA, f.id)
        assertEquals(7, f.size)
        assertEquals(0x01, f.u(0))
        assertEquals(0x10, f.u(1))
        assertEquals(0x00, f.u(6))
    }

    @Test
    fun `splits a 19 character frame into 8 data bytes`() {
        val splitter = FrameSplitter()
        val frames = splitter.feed("2980000000000000000\r".toByteArray())
        assertEquals(1, frames.size)
        assertEquals(0x298, frames[0].id)
        assertEquals(8, frames[0].size)
    }

    @Test
    fun `reassembles a frame split across chunk boundaries`() {
        val splitter = FrameSplitter()
        assertTrue(splitter.feed("6FA01100000000".toByteArray()).isEmpty())
        assertTrue(splitter.feed("0".toByteArray()).isEmpty())
        val frames = splitter.feed("00\r".toByteArray())
        assertEquals(1, frames.size)
        assertEquals(0x6FA, frames[0].id)
    }

    @Test
    fun `discards lines that are not 17 or 19 characters`() {
        val splitter = FrameSplitter()
        assertTrue(splitter.feed("SEARCHING...\r".toByteArray()).isEmpty())
        assertTrue(splitter.feed("6FA0110000000\r".toByteArray()).isEmpty())
    }

    @Test
    fun `handles several frames in one chunk`() {
        val splitter = FrameSplitter()
        val frames = splitter.feed("6FA01100000000000\r2980000000000000000\r".toByteArray())
        assertEquals(2, frames.size)
        assertEquals(0x6FA, frames[0].id)
        assertEquals(0x298, frames[1].id)
    }

    // ---- LeafCanParser -------------------------------------------------

    private fun frame(id: Int, vararg bytes: Int) = CanFrame(id, ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `decodes motor rpm from 0x298`() {
        val parser = LeafCanParser()
        // 0x29CE = 10702 -> 10702 - 10000 = 702 rpm
        parser.accept(frame(0x298, 0, 0, 0, 0, 0, 0, 0x29, 0xCE))
        assertEquals(702, parser.signals().motorRpm)
    }

    @Test
    fun `clamps near-zero rpm to zero`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x298, 0, 0, 0, 0, 0, 0, 0x27, 0x10)) // 10005 -> 5 rpm
        assertEquals(0, parser.signals().motorRpm)
    }

    @Test
    fun `decodes state of charge from 0x374`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x374, 0, 60, 0, 0, 0, 0, 0, 0))
        assertEquals(25.0, parser.signals().soc!!, 0.001)
    }

    @Test
    fun `decodes pack current and voltage from 0x373`() {
        val parser = LeafCanParser()
        // current = 1234 / 100 = 12.34 A, voltage = 3600 / 10 = 360.0 V
        parser.accept(frame(0x373, 0, 0, 0x04, 0xD2, 0x0E, 0x10, 0, 0))
        val s = parser.signals()
        assertEquals(12.34, s.packCurrent!!, 0.001)
        assertEquals(360.0, s.packVoltage!!, 0.001)
        assertEquals(12.34 * 360.0, s.packPower!!, 0.01)
    }

    @Test
    fun `decodes gear from 0x418`() {
        val parser = LeafCanParser()
        // 0x418 carries 7 data bytes (a 17 character line)
        parser.accept(frame(0x418, 68, 0, 0, 0, 0, 0, 0))
        assertEquals('D', parser.signals().gear)
    }

    @Test
    fun `ignores unknown gear values`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x418, 99, 0, 0, 0, 0, 0, 0))
        assertNull(parser.signals().gear)
    }

    @Test
    fun `decodes odometer from 0x412`() {
        val parser = LeafCanParser(odometerDivisor = 57.75)
        val raw = 1_000_000
        // the 24-bit odometer counter occupies bytes 2..4
        parser.accept(
            frame(
                0x412,
                0, 0,
                (raw shr 16) and 0xFF, (raw shr 8) and 0xFF, raw and 0xFF,
                0, 0, 0,
            )
        )
        assertEquals(raw, parser.signals().odometerRaw)
        assertEquals(((raw / 57.75) * 1000.0).toInt(), parser.signals().odometerMiles)
    }

    @Test
    fun `decodes amp hours from 0x762`() {
        val parser = LeafCanParser()
        // 0x0190 = 400 -> 400 / 10 = 40.0 Ah
        parser.accept(frame(0x762, 36, 0, 0, 0x01, 0x90, 0, 0, 0))
        assertEquals(40.0, parser.signals().ampHours!!, 0.001)
    }

    @Test
    fun `decodes charging flag from 0x346`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x346, 0, 0, 0, 0, 0x20, 0, 0, 0))
        assertEquals(true, parser.signals().chargingFlag)
        parser.accept(frame(0x346, 0, 0, 0, 0, 0x00, 0, 0, 0))
        assertEquals(false, parser.signals().chargingFlag)
    }

    @Test
    fun `decodes cell voltages and temperatures from 0x6E1`() {
        val parser = LeafCanParser()
        // 8 data bytes: group, unused, temp0, temp1, volt0_hi, volt0_lo, volt1_hi, volt1_lo
        // 0x0064 = 100 -> 100/200 = 0.5 V, +2.1 = 2.6 V
        parser.accept(frame(0x6E1, 1, 0, 60, 61, 0x00, 0x64, 0x00, 0x64))
        val v = parser.cellVoltages()
        assertEquals(2.6, v[0]!!, 0.0001)
        assertEquals(2.6, v[1]!!, 0.0001)
        // temperature bytes 2 and 3 -> minus 50
        assertEquals(10, parser.cellTemperatures()[0])
        assertEquals(11, parser.cellTemperatures()[1])
    }

    @Test
    fun `rejects cell frames with an out of range group`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x6E1, 99, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(parser.cellVoltages().all { it == null })
    }

    @Test
    fun `detects a 96 cell pack from a group index above four`() {
        val parser = LeafCanParser()
        parser.accept(frame(0x6E1, 1, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(false, parser.signals().is96CellPack)
        parser.accept(frame(0x6E1, 5, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(true, parser.signals().is96CellPack)
        assertEquals(96, parser.detectedCellCount)
    }

    @Test
    fun `assembles the VIN from 0x29A and 0x6FA`() {
        val parser = LeafCanParser()
        // part 0: 7 characters, part 1: 7 characters, part 2: 3 characters
        parser.accept(frame(0x6FA, 0, *("JNMEAB1".map { it.code }.toIntArray())))
        parser.accept(frame(0x29A, 1, *("2345678".map { it.code }.toIntArray())))
        assertNull(parser.signals().vin)
        parser.accept(frame(0x29A, 2, *("XYZ".map { it.code }.toIntArray())))
        assertEquals("JNMEAB12345678XYZ", parser.signals().vin)
    }

    // ---- init sequence -------------------------------------------------

    @Test
    fun `init sequence uses only ELM327 commands`() {
        assertTrue(
            "no STN1110-only command may appear in the live path",
            Elm327InitSequence.FULL.none { it.startsWith("ST") },
        )
    }

    @Test
    fun `init sequence ends with the live monitor`() {
        assertEquals("ATMA", Elm327InitSequence.FULL.last())
        assertEquals("ATCAF0", Elm327InitSequence.FULL[Elm327InitSequence.FULL.size - 2])
    }

    @Test
    fun `init sequence configures headers on and spaces off`() {
        assertTrue(Elm327InitSequence.FULL.contains("ATH1"))
        assertTrue(Elm327InitSequence.FULL.contains("ATS0"))
        assertTrue(Elm327InitSequence.FULL.contains("ATSP6"))
    }
}
