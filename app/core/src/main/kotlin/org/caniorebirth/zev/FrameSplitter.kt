package org.caniorebirth.zev

/**
 * Splits a byte stream from the OBD adapter into [CanFrame]s.
 *
 * The adapter emits one line per frame, terminated by CR (0x0D), in the
 * compact header-on / space-off layout. A line is only accepted when it is
 * 17 or 19 characters long; anything else is a partial or garbled frame and
 * is discarded. The CAN identifier is the first three hex characters and the
 * remaining characters are the data bytes.
 */
class FrameSplitter {

    private val buffer = StringBuilder()

    /**
     * Feed raw bytes read from the adapter and return every complete frame
     * that could be decoded.
     */
    fun feed(chunk: ByteArray): List<CanFrame> {
        val frames = mutableListOf<CanFrame>()
        for (b in chunk) {
            when (val c = b.toInt().toChar()) {
                '\r' -> {
                    decode(buffer.toString())?.let { frames.add(it) }
                    buffer.setLength(0)
                }
                '\n' -> Unit // linefeeds are disabled, but tolerate them
                else -> {
                    if (buffer.length < MAX_LINE) buffer.append(c)
                }
            }
        }
        return frames
    }

    /** Discard any partially received line. */
    fun reset() = buffer.setLength(0)

    private fun decode(line: String): CanFrame? {
        if (line.length != SHORT_FRAME_LEN && line.length != LONG_FRAME_LEN) return null
        val id = line.substring(0, 3).toIntOrNull(16) ?: return null
        val byteCount = (line.length - 3) / 2
        val data = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            val hi = hexValue(line[3 + i * 2]) ?: return null
            val lo = hexValue(line[3 + i * 2 + 1]) ?: return null
            data[i] = ((hi shl 4) or lo).toByte()
        }
        return CanFrame(id, data)
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    companion object {
        const val SHORT_FRAME_LEN = 17
        const val LONG_FRAME_LEN = 19
        const val MAX_LINE = 64
    }
}
