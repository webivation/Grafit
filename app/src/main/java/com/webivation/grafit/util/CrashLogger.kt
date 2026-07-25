package com.webivation.grafit.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves crash logs to persistent storage on the device so crashes can be
 * investigated later. Logs are written to:
 * - App-specific external cache (primary): accessible via Termux
 * - Falls back to internal storage if external is unavailable
 *
 * On Android 6.0+, external cache doesn't require WRITE_EXTERNAL_STORAGE permission.
 *
 * ACCESS VIA TERMUX:
 * 1. Open Termux and run:
 *    ls ~/Android/data/com.webivation.grafit/cache/crashes/
 * 2. View logs:
 *    cat ~/Android/data/com.webivation.grafit/cache/crashes/crash_YYYY-MM-DD_HH-mm-ss-SSS.log
 * 3. Or view with logcat:
 *    adb logcat -s CrashLogger,MainActivity,GrafitApplication
 *
 * LOGS WRITTEN BY:
 * - CrashLogger: Uncaught exceptions (startup crashes)
 * - CrashLogger.logException(): Manual logging for non-fatal errors
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 10
    private const val TIMESTAMP_FORMAT = "yyyy-MM-dd_HH-mm-ss-SSS"

    /**
     * Initialize crash logging. Call this once in Application.onCreate().
     * Sets up an uncaught exception handler to log all crashes.
     *
     * This function is designed to be very defensive - it logs errors but
     * never throws exceptions itself.
     */
    fun init(context: Context) {
        try {
            // Try to ensure logs directory exists
            try {
                val logsDir = getCrashLogsDir(context)
                if (!logsDir.exists() && !logsDir.mkdirs()) {
                    Log.w(TAG, "Failed to create crash logs directory")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while creating crash directory", e)
                // Continue anyway - the directory creation might fail but we should still set up the handler
            }

            // Set up the uncaught exception handler
            try {
                val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        logCrash(context, thread, throwable)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to log crash in exception handler", e)
                    }
                    // Re-throw to let Android handle the crash (show crash dialog)
                    try {
                        defaultHandler?.uncaughtException(thread, throwable)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in default exception handler", e)
                    }
                }
                Log.d(TAG, "Crash logging initialized, logs: ${getCrashLogsDir(context)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up exception handler", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during CrashLogger.init()", e)
        }
    }

    /**
     * Manually log an exception/error. Useful for non-fatal errors that still
     * warrant investigation.
     */
    fun logException(context: Context, exception: Throwable, tag: String? = null) {
        try {
            val logsDir = getCrashLogsDir(context)
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                Log.w(TAG, "Failed to create crash logs directory")
                return
            }

            val timestamp = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date())
            val filename = "exception_${timestamp}.log"
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
                Log.e(TAG, "Failed to write exception log", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception", e)
        }
    }

    /**
     * Get the location where crash logs are stored.
     * 
     * Primary: /sdcard/Android/data/com.webivation.grafit/cache/crashes/
     * (accessible via Termux without special permissions)
     * 
     * Fallback: /data/data/com.webivation.grafit/cache/crashes/
     * (if external storage is unavailable)
     */
    fun getCrashLogsDir(context: Context): File {
        // Try external cache first (accessible via Termux, no permissions needed on Android 6.0+)
        val externalCacheDir = context.externalCacheDir
        if (externalCacheDir != null && externalCacheDir.canWrite()) {
            return File(externalCacheDir, CRASH_DIR)
        }

        // Fallback to internal cache
        return File(context.cacheDir, CRASH_DIR)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val logsDir = getCrashLogsDir(context)
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                Log.w(TAG, "Failed to create crash logs directory")
                return
            }

            val timestamp = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date())
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
                Log.e(TAG, "Failed to write crash log", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    private fun trimOldCrashLogs(logsDir: File) {
        try {
            val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (files.size > MAX_CRASH_FILES) {
                files.drop(MAX_CRASH_FILES).forEach { oldFile ->
                    if (!oldFile.delete()) {
                        Log.w(TAG, "Failed to delete old crash log: ${oldFile.path}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim old crash logs", e)
        }
    }
}
