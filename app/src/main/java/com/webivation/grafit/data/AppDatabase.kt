package com.webivation.grafit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BufferedMetric::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metricDao(): MetricDao

    companion object {
        private const val DB_NAME = "grafit_buffer.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
    }
}
