package com.webivation.grafit

import android.app.Application
import com.webivation.grafit.data.AppDatabase
import com.webivation.grafit.util.CrashLogger

class GrafitApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
