package org.caniorebirth.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val msg = "Thread: ${thread.name}\n${sw}"
                Log.e("CrashLogger", msg, throwable)
                val dir = File(context.getExternalFilesDir(null), "crash")
                dir.mkdirs()
                File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(msg)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
