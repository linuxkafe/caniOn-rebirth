package org.caniorebirth.android

import org.caniorebirth.zev.ZevCanParser
import org.caniorebirth.zev.ZevSignals

/**
 * Shared state holder for the reimplementation. The parser is stateful and
 * shared across fragments so they all see the same live data.
 */
object CanionState {
    val parser = ZevCanParser()
    val socHistory = mutableListOf<Float>()
    val voltHistory = mutableListOf<Float>()
    val currHistory = mutableListOf<Float>()
    val cellSpreadHistory = mutableListOf<Float>()
    const val MAX_HISTORY = 120

    fun signals(): ZevSignals = parser.signals()

    fun appendHistory(sig: ZevSignals) {
        sig.soc?.let { socHistory.add(it.toFloat()); trim(socHistory) }
        sig.packVoltage?.let { voltHistory.add(it.toFloat()); trim(voltHistory) }
        sig.packCurrent?.let { currHistory.add(it.toFloat()); trim(currHistory) }
        val cells = sig.cellVoltages.filterNotNull()
        if (cells.size >= 2) {
            cellSpreadHistory.add((cells.maxOrNull()!! - cells.minOrNull()!!).toFloat())
            trim(cellSpreadHistory)
        }
    }

    private fun trim(list: MutableList<Float>) {
        while (list.size > MAX_HISTORY) list.removeAt(0)
    }
}
