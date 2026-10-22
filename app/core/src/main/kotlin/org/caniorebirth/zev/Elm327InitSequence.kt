package org.caniorebirth.zev

/**
 * The command sequence an ELM327 adapter needs before it will stream the
 * ZEV CAN bus.
 *
 * The sequence is deliberately **ELM327-only**. The original client mixed
 * STN1110 commands (`STFCP`, `STFAP`, `STM`) into the live path, which
 * excluded every ELM327-compatible adapter — including the Vgate iCar Pro
 * 2S — from receiving data even though the connection itself succeeded.
 *
 * See `docs/PROTOCOL.md` §3 for the rationale.
 */
object Elm327InitSequence {

    const val CR = '\r'

    /**
     * The live-monitor command set.
     *
     * `ATCAF0` resets the CAN acceptance filter to "accept everything" and
     * `ATMA` starts the monitor. The ZEV client dispatches on the CAN
     * identifier anyway, so hardware-side filtering is an optimisation, not a
     * requirement — and ELM327 cannot express the original multi-identifier
     * `STFAP` list in a single command.
     */
    val LIVE_MONITOR: List<String> = listOf(
        "ATCAF0",
        "ATMA",
    )

    /**
     * The full bring-up sequence, in order.
     *
     * Steps 1–8 configure the adapter; steps 9–12 request the 0x761 cell
     * report (the ZEV's cell voltage/temperature request); steps 13+ enter
     * the live monitor.
     */
    val FULL: List<String> = listOf(
        "",             // flush
        "",             // flush
        "ATZ",          // reset adapter
        "ATSP6",        // CAN 11-bit, 500 kbit/s
        "ATE0",         // echo off
        "ATH1",         // headers on
        "ATL0",         // linefeeds off
        "ATS0",         // spaces off
        "ATFCSH761",    // flow-control header 0x761
        "ATFCSD300000", // flow-control data: 30 00 00
        "ATFCSM1",      // flow-control mode 1
        "ATSH761",      // set header 0x761
        "2101",         // request payload for the cell report
    ) + LIVE_MONITOR

    /** Render a command for the wire: CR-terminated, no newline. */
    fun encode(command: String): ByteArray = (command + CR).toByteArray(Charsets.UTF_8)
}
