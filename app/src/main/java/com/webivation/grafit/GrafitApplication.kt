package com.webivation.grafit

import android.app.Application
import com.webivation.grafit.data.AppDatabase

class GrafitApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
    }
}
