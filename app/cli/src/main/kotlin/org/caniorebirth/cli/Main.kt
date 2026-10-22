package org.caniorebirth.cli

import org.caniorebirth.leaf.Elm327InitSequence
import org.caniorebirth.leaf.FrameSplitter
import org.caniorebirth.leaf.LeafCanParser
import org.caniorebirth.leaf.LeafSignals
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream

/**
 * A command-line Leaf client.
 *
 * This exists so the protocol engine can be built, run and tested **without
 * the Android SDK and without the original APK** — only a JDK is required.
 * It talks to an ELM327 adapter over any byte stream:
 *
 *   * a serial port (`/dev/ttyUSB0`, `COM3`, …) — a USB-OTG ELM327 cable
 *   * a TCP socket (a Bluetooth SPP bridge, or `socat` over RFCOMM)
 *   * stdin, for replaying a captured session
 *
 * ```
 * caniOn-cli --serial /dev/ttyUSB0
 * caniOn-cli --tcp localhost:35000
 * caniOn-cli --stdin < capture.log
 * ```
 */
object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        if (options.help) {
            printUsage()
            return
        }

        val transport = openTransport(options)
        val parser = LeafCanParser(odometerDivisor = options.odometerDivisor)
        val splitter = FrameSplitter()

        println("caniOn Rebirth — Leaf OBD client")
        println("adapter : ${options.describe()}")
        println("divisor : ${options.odometerDivisor}")
        println("sending the ELM327 bring-up sequence…")

        for (command in Elm327InitSequence.FULL) {
            if (command.isEmpty()) continue
            transport.write(Elm327InitSequence.encode(command))
            Thread.sleep(options.commandDelayMs)
        }

        println("streaming. press Ctrl-C to stop.")
        println()
        println("  time        soc    pack V    pack A    pack W   rpm  gear   odo")
        println("  " + "-".repeat(74))

        val started = System.currentTimeMillis()
        var lastReport = 0L
        val buffer = ByteArray(4096)
        while (true) {
            val n = transport.read(buffer)
            if (n < 0) break
            for (frame in splitter.feed(buffer.copyOf(n))) {
                parser.accept(frame)
            }
            val now = System.currentTimeMillis()
            if (now - lastReport >= options.reportIntervalMs) {
                lastReport = now
                report(parser.signals(), started)
            }
        }
        // Final report, so a short capture (or a replay from stdin) still
        // shows what was decoded.
        report(parser.signals(), started)
    }

    private fun report(s: LeafSignals, started: Long) {
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val soc = s.soc?.let { "%.0f%%".format(it) } ?: "  --  "
        val volt = s.packVoltage?.let { "%.1fV".format(it) } ?: "  --  "
        val amp = s.packCurrent?.let { "%.1fA".format(it) } ?: "  --  "
        val watt = s.packPower?.let { "%.0fW".format(it) } ?: "  --  "
        val rpm = s.motorRpm?.toString() ?: "--"
        val gear = s.gear?.toString() ?: "--"
        val odo = s.odometerMiles?.toString() ?: "--"
        println("  %5ds  %6s %8s %8s %8s %5s %5s %6s".format(elapsed, soc, volt, amp, watt, rpm, gear, odo))

        val cells = s.cellVoltages.count { it != null }
        if (cells > 0) {
            val first = s.cellVoltages.take(4).joinToString(" ") { v ->
                v?.let { "%.2f".format(it) } ?: " -- "
            }
            println("         cells: $cells known, ${if (s.is96CellPack == true) "96-cell" else "24-cell"} pack, first: $first")
        }
        s.vin?.let { println("         vin: $it") }
    }

    private fun openTransport(options: Options): Transport = when {
        options.serial != null -> SerialTransport(options.serial)
        options.tcp != null -> TcpTransport(options.tcp)
        else -> StdinTransport()
    }

    private fun printUsage() {
        val out: PrintStream = System.out
        out.println(
            """
            caniOn Rebirth — Leaf OBD client

            Usage:
              caniOn-cli --serial <device>     talk to an ELM327 over a serial port
              caniOn-cli --tcp <host:port>     talk to an ELM327 over TCP
              caniOn-cli --stdin               read a captured session from stdin

            Options:
              --serial <device>       serial port, e.g. /dev/ttyUSB0 or COM3
              --tcp <host:port>       TCP endpoint of a Bluetooth SPP bridge
              --stdin                 read from standard input (default)
              --divisor <number>      odometer divisor (default 57.75)
              --report <ms>           report interval in milliseconds (default 1000)
              --delay <ms>            delay between init commands (default 250)
              --help                  show this help
            """.trimIndent()
        )
    }

    /** A bidirectional byte stream to an adapter. */
    interface Transport {
        fun write(bytes: ByteArray)
        fun read(buffer: ByteArray): Int
    }

    /** Reads from standard input; used for replaying a captured session. */
    private class StdinTransport : Transport {
        private val reader: BufferedReader = BufferedReader(InputStreamReader(System.`in`))
        private var pending: String? = null

        override fun write(bytes: ByteArray) {
            // stdin is read-only in this mode
        }

        override fun read(buffer: ByteArray): Int {
            val line = reader.readLine() ?: return -1
            pending = line
            val bytes = (pending ?: "").toByteArray(Charsets.UTF_8)
            val n = minOf(bytes.size, buffer.size)
            System.arraycopy(bytes, 0, buffer, 0, n)
            return n
        }
    }

    /**
     * A real serial port.
     *
     * The JDK has no public serial-port API, so the line discipline is set
     * with `stty` (115200 8N1, raw) and the port is then read and written as
     * plain streams. On systems without `stty` the port is opened as-is.
     */
    private class SerialTransport(private val device: String) : Transport {
        private val input: InputStream
        private val out: OutputStream

        init {
            val file = java.io.File(device)
            if (!file.exists()) {
                throw IllegalArgumentException("serial device not found: $device")
            }
            configureLine(device)
            input = java.io.FileInputStream(file)
            out = java.io.FileOutputStream(file)
        }

        override fun write(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
        }

        override fun read(buffer: ByteArray): Int = input.read(buffer)

        companion object {
            private fun configureLine(device: String) {
                val candidates = listOf(
                    listOf("stty", "-F", device, "115200", "raw", "-echo"),
                    listOf("stty", "-f", device, "115200", "raw", "-echo"),
                )
                for (cmd in candidates) {
                    try {
                        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                        if (p.waitFor() == 0) return
                    } catch (_: Exception) {
                        // try the next form
                    }
                }
                // No stty: assume the port is already configured.
            }
        }
    }

    /** A TCP socket, typically a Bluetooth SPP bridge. */
    private class TcpTransport(endpoint: String) : Transport {
        private val socket = java.net.Socket(endpoint.substringBefore(':'), endpoint.substringAfter(':').toInt())
        private val out = socket.getOutputStream()
        private val input = socket.getInputStream()

        override fun write(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
        }

        override fun read(buffer: ByteArray): Int = input.read(buffer)
    }

    /** Command-line options. */
    private class Options(
        val serial: String?,
        val tcp: String?,
        val stdin: Boolean,
        val odometerDivisor: Double,
        val reportIntervalMs: Long,
        val commandDelayMs: Long,
        val help: Boolean,
    ) {
        fun describe(): String = serial ?: tcp ?: "stdin"

        companion object {
            fun parse(args: Array<String>): Options {
                var serial: String? = null
                var tcp: String? = null
                var stdin = false
                var divisor = LeafCanParser.DEFAULT_ODOMETER_DIVISOR
                var report = 1000L
                var delay = 250L
                var help = false
                var i = 0
                while (i < args.size) {
                    when (args[i]) {
                        "--serial" -> { serial = args.getOrNull(++i); }
                        "--tcp" -> { tcp = args.getOrNull(++i); }
                        "--stdin" -> stdin = true
                        "--divisor" -> divisor = args.getOrNull(++i)?.toDoubleOrNull() ?: divisor
                        "--report" -> report = args.getOrNull(++i)?.toLongOrNull() ?: report
                        "--delay" -> delay = args.getOrNull(++i)?.toLongOrNull() ?: delay
                        "--help", "-h" -> help = true
                        else -> {
                            System.err.println("unknown option: ${args[i]}")
                            help = true
                        }
                    }
                    i++
                }
                return Options(serial, tcp, stdin, divisor, report, delay, help)
            }
        }
    }
}
