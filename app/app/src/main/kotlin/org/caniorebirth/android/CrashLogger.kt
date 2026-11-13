package org.caniorebirth.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    fun logDir(context: Context): File {
        val prefs = context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE)
        val custom = prefs.getString("log_dir", null)
        return if (!custom.isNullOrBlank()) File(custom) else File(context.getExternalFilesDir(null), "crash")
    }

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val msg = "Thread: ${thread.name}\n${sw}"
                Log.e("CrashLogger", msg, throwable)
                val dir = logDir(context)
                dir.mkdirs()
                File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(msg)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
