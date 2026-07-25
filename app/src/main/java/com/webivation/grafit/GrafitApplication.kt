package com.webivation.grafit

import android.app.Application
import android.util.Log
import com.webivation.grafit.data.AppDatabase
import com.webivation.grafit.util.CrashLogger

class GrafitApplication : Application() {

    val database: AppDatabase by lazy {
        try {
            AppDatabase.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database", e)
            CrashLogger.logException(this, e, TAG)
            throw e
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            CrashLogger.init(this)
            Log.i(TAG, "Grafit application started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during application initialization", e)
            // Don't re-throw - let the app continue with limited functionality
        }
    }

    companion object {
        private const val TAG = "GrafitApplication"
    }
}
