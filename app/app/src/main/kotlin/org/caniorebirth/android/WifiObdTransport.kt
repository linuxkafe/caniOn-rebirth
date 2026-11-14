package org.caniorebirth.android

import android.content.Context
import org.caniorebirth.zev.ObdClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WiFi (TCP) transport for [ObdClient].
 *
 * Connects to an ELM327-family WiFi adapter (e.g. Vgate iCar Pro 2S in WiFi
 * mode) over a plain TCP socket. The default host/port follow the convention
 * used by most WiFi OBD dongles (192.168.0.10:35000).
 */
class WifiObdTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
) : ObdClient.Transport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    var onBytes: ((ByteArray) -> Unit)? = null

    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    @Volatile
    private var running = false

    override fun open() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 10000)
            socket = s
            input = s.inputStream
            output = s.outputStream
            running = true
            Thread({ readLoop() }, "obd-wifi-reader").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            running = false
            try { socket?.close() } catch (_: Exception) { }
            socket = null
            onError?.invoke(e)
            throw e
        }
    }

    override fun write(bytes: ByteArray) {
        output?.write(bytes)
        output?.flush()
    }

    override fun close() {
        running = false
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        input = null
        output = null
    }

    private fun readLoop() {
        val stream = input ?: return
        val buffer = ByteArray(1024)
        try {
            while (running) {
                val n = stream.read(buffer)
                if (n > 0) {
                    onBytes?.invoke(buffer.copyOf(n))
                } else if (n < 0) {
                    break
                }
            }
        } catch (e: IOException) {
            if (running) onError?.invoke(e)
        }
    }
}
