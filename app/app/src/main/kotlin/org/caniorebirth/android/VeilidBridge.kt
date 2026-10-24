package org.caniorebirth.android

object VeilidBridge {
    init {
        try {
            System.loadLibrary("veilid_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("VeilidBridge", "Failed to load native library", e)
        }
    }

    external fun veilidInit(programName: String, namespace: String): Int
    external fun veilidAttach(): Int
    external fun veilidDetach(): Int
    external fun veilidGetState(): Int
    external fun veilidShutdown()
    external fun veilidVersion(): String?
}
