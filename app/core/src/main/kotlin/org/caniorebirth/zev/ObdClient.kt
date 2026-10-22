package org.caniorebirth.zev

/**
 * A transport-agnostic OBD client: it owns the command sequence and the
 * frame parsing, and delegates the bytes to a [Transport].
 *
 * The Android Bluetooth implementation lives in the `app` module; this class
 * is pure JVM so it can be unit tested without a device.
 */
class ObdClient(
    private val transport: Transport,
    private val parser: ZevCanParser = ZevCanParser(),
) {

    /** Signals observed so far. */
    val signals: ZevSignals get() = parser.signals()

    /** Raised when the adapter answers a command with an error or `ERROR`. */
    var onAdapterError: ((String) -> Unit)? = null

    /** Raised for each decoded frame. */
    var onFrame: ((CanFrame) -> Unit)? = null

    /** True once the live monitor has been started. */
    var isMonitoring: Boolean = false
        private set

    private val splitter = FrameSplitter()
    private val pending = StringBuilder()
    private var queue: List<String> = emptyList()
    private var awaitingPrompt = false

    /**
     * Connect, run the bring-up sequence and start the live monitor.
     */
    fun start() {
        transport.open()
        splitter.reset()
        pending.setLength(0)
        queue = Elm327InitSequence.FULL.toMutableList()
        isMonitoring = false
        pump()
    }

    /** Stop the monitor and close the transport. */
    fun stop() {
        queue = emptyList()
        isMonitoring = false
        try {
            transport.write(Elm327InitSequence.encode("ATZ"))
        } catch (_: Exception) {
            // The adapter may already be gone; nothing useful to do.
        }
        transport.close()
    }

    /** Bytes arriving from the transport. */
    fun onBytes(chunk: ByteArray) {
        for (b in chunk) {
            val c = b.toInt().toChar()
            when {
                c == '>' -> {
                    awaitingPrompt = false
                    pending.setLength(0)
                    pump()
                }

                c == '\r' -> {
                    val line = pending.toString().trim()
                    pending.setLength(0)
                    handleLine(line)
                }

                c == '\n' -> Unit

                else -> {
                    if (pending.length < 4096) pending.append(c)
                }
            }
        }
    }

    private fun handleLine(line: String) {
        if (line.isEmpty()) return
        if (line.startsWith("ERROR") || line.startsWith("?")) {
            onAdapterError?.invoke(line)
            return
        }
        // The adapter echoes the last command during ATZ; ignore it.
        if (line.startsWith("AT")) return

        val frames = splitter.feed(line.toByteArray(Charsets.UTF_8))
        for (f in frames) {
            onFrame?.invoke(f)
            parser.accept(f)
        }
    }

    private fun pump() {
        if (awaitingPrompt) return
        val next = queue.firstOrNull() ?: return
        queue = queue.drop(1)
        transport.write(Elm327InitSequence.encode(next))
        awaitingPrompt = true
        if (next == "ATMA") isMonitoring = true
    }

    /** Byte-level transport to an OBD adapter. */
    interface Transport {
        fun open()
        fun write(bytes: ByteArray)
        fun close()
    }
}
