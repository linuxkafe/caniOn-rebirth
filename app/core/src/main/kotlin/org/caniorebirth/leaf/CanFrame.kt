package org.caniorebirth.leaf

/**
 * A single decoded CAN frame from the Leaf bus.
 *
 * The wire format is the compact ELM327 monitor layout:
 *  - 3 hex characters: 11-bit CAN identifier
 *  - 2 hex characters per data byte
 *  - total line length 17 (7 data bytes) or 19 (8 data bytes)
 *  - terminated by CR
 */
data class CanFrame(
    val id: Int,
    val data: ByteArray,
) {
    /** Number of data bytes carried by this frame. */
    val size: Int get() = data.size

    /**
     * Unsigned data byte, or 0 when the index is out of range.
     *
     * The Leaf protocol uses unsigned bytes throughout; signedness is applied
     * explicitly at the point of use.
     */
    fun u(index: Int): Int = if (index in data.indices) data[index].toInt() and 0xFF else 0

    /** Big-endian unsigned 16-bit value from two bytes. */
    fun u16(index: Int): Int = (u(index) shl 8) or u(index + 1)

    /** Big-endian unsigned 24-bit value from three bytes. */
    fun u24(index: Int): Int = (u(index) shl 16) or (u(index + 1) shl 8) or u(index + 2)

    override fun toString(): String =
        "%03X %s".format(id, data.joinToString("") { "%02X".format(it) })
}
