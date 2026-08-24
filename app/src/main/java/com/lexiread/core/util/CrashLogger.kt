package com.lexiread.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exception stack traces to a local file so crashes can be
 * diagnosed even without adb/logcat attached. The original handler is always
 * invoked so the system crash dialog / process death behaves normally.
 */
object CrashLogger {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { append(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Reads and clears the saved crash log (used to surface the last crash). */
    fun consumeLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.length() > MAX_FILE_BYTES) file.delete()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(context.filesDir, FILE_NAME).appendText(
            buildString {
                appendLine("=== CRASH $timestamp (thread: ${thread.name}) ===")
                appendLine(Log.getStackTraceString(throwable))
            }
        )
    }
}
