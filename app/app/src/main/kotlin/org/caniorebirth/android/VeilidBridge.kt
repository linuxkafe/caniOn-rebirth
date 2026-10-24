package org.caniorebirth.android

/**
 * JNI bridge to the native Veilid wrapper (libveilid_wrapper.so).
 * The native library wraps veilid-core and exposes C-ABI/JNI functions.
 */
object VeilidBridge {
    init {
        try {
            System.loadLibrary("veilid_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("VeilidBridge", "Failed to load native library", e)
        }
    }

    external fun veilidInit(programName: String, namespace: String): Int
    external fun veilidGetState(): Int
    external fun veilidShutdown()
    external fun veilidVersion(): String?
}
