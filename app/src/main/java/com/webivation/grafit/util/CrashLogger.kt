package com.webivation.grafit.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves crash logs to persistent storage on the device so crashes can be
 * investigated later by connecting via ADB or accessing app-specific storage.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 10

    /**
     * Initialize crash logging. Call this once in Application.onCreate().
     * Sets up an uncaught exception handler to log all crashes.
     */
    fun init(context: Context) {
        val logsDir = getCrashLogsDir(context)
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.e(TAG, "Failed to create crash logs directory during init")
            return
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(context, thread, throwable)
            // Re-throw to let Android handle the crash (show crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Crash logging initialized")
    }

    /**
     * Manually log an exception/error. Useful for non-fatal errors that still
     * warrant investigation.
     */
    fun logException(context: Context, exception: Throwable, tag: String? = null) {
        val logsDir = getCrashLogsDir(context)
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.e(TAG, "Failed to create crash logs directory")
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val filename = "error_${timestamp}.log"
        val file = File(logsDir, filename)

        try {
            FileWriter(file).use { writer ->
                writer.append("Timestamp: $timestamp\n")
                writer.append("Tag: ${tag ?: "N/A"}\n")
                writer.append("Exception: ${exception::class.simpleName}\n")
                writer.append("Message: ${exception.message}\n")
                writer.append("\nStack Trace:\n")
                writer.append(exception.stackTraceToString())
            }
            Log.e(TAG, "Logged exception to $filename")
            trimOldCrashLogs(logsDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception", e)
        }
    }

    /**
     * Get the location where crash logs are stored. Typically:
     * /data/data/com.webivation.grafit/files/crashes/
     */
    fun getCrashLogsDir(context: Context): File =
        File(context.filesDir, CRASH_DIR)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        val logsDir = getCrashLogsDir(context)
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.e(TAG, "Failed to create crash logs directory")
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val filename = "crash_${timestamp}.log"
        val file = File(logsDir, filename)

        try {
            FileWriter(file).use { writer ->
                writer.append("=== GRAFIT CRASH LOG ===\n")
                writer.append("Timestamp: $timestamp\n")
                writer.append("Thread: ${thread.name}\n")
                writer.append("Exception: ${throwable::class.simpleName}\n")
                writer.append("Message: ${throwable.message}\n")
                writer.append("\nFull Stack Trace:\n")
                writer.append(throwable.stackTraceToString())
            }
            Log.e(TAG, "Crash logged to $filename")
            trimOldCrashLogs(logsDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    private fun trimOldCrashLogs(logsDir: File) {
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CRASH_FILES) {
            files.drop(MAX_CRASH_FILES).forEach { oldFile ->
                oldFile.delete()
            }
        }
    }
}
