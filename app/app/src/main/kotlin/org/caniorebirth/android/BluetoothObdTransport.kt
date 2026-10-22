package org.caniorebirth.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import org.caniorebirth.leaf.ObdClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth SPP transport for [ObdClient].
 *
 * Connects to an already-paired ELM327 adapter over the standard Serial Port
 * Profile UUID and streams bytes in both directions.
 *
 * ### Android 12+ permissions
 *
 * `BLUETOOTH_CONNECT` is a **runtime** permission from Android 12 (API 31).
 * Declaring it in the manifest is not enough — the user must grant it before
 * [connect] is called, or the platform throws `SecurityException`.
 */
class BluetoothObdTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdClient.Transport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    var onBytes: ((ByteArray) -> Unit)? = null

    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    @Volatile
    private var running = false

    override fun open() {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = s
        s.connect()
        input = s.inputStream
        output = s.outputStream
        running = true
        Thread({ readLoop() }, "obd-bt-reader").apply { isDaemon = true }.start()
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

    companion object {
        /** Standard Bluetooth Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * The bonded devices that could plausibly be an OBD adapter.
         *
         * Requires `BLUETOOTH_CONNECT` on Android 12+.
         */
        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        fun candidateAdapters(context: Context): List<BluetoothDevice> {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return try {
                adapter.bondedDevices.orEmpty().toList()
            } catch (_: SecurityException) {
                emptyList()
            }
        }

        /** True when the platform requires a runtime Bluetooth grant. */
        fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
