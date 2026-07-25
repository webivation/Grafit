package com.webivation.grafit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.webivation.grafit.MainActivity
import com.webivation.grafit.R
import com.webivation.grafit.data.AppDatabase
import com.webivation.grafit.data.BufferedMetric
import com.webivation.grafit.network.PrometheusLabel
import com.webivation.grafit.network.PrometheusRemoteWriter
import com.webivation.grafit.network.PrometheusSample
import com.webivation.grafit.network.PrometheusTimeSeries
import com.webivation.grafit.ring.R02BleManager
import com.webivation.grafit.ring.RingMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  1. Maintains a BLE connection to the R02 ring via [R02BleManager].
 *  2. Converts incoming [RingMetric] readings to Prometheus time-series.
 *  3. Tries to flush metrics to Grafana Cloud via [PrometheusRemoteWriter].
 *  4. On failure, persists metrics to the local Room buffer ([AppDatabase])
 *     and retries on the next flush cycle.
 *  5. Trims the buffer to [Prefs.bufferMaxRows] to cap disk usage.
 */
class DataSyncService : LifecycleService() {

    private lateinit var bleManager: R02BleManager
    private lateinit var db: AppDatabase

    // Created lazily after reading SharedPreferences
    private var writer: PrometheusRemoteWriter? = null

    private var deviceLabel = "R02"
    /** Pre-computed once after [initFromPrefs] to avoid repeated string concatenation. */
    private var commonLabels = "device=$deviceLabel,source=grafit"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to ring…"))

        db = AppDatabase.getInstance(this)
        initFromPrefs()
        startBle()
        startFlushLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    private fun initFromPrefs() {
        val prefs = Prefs.get(this)
        deviceLabel = prefs.deviceName
        commonLabels = "device=$deviceLabel,source=grafit"
        writer = if (prefs.endpointUrl.isNotBlank() && prefs.username.isNotBlank()) {
            PrometheusRemoteWriter(prefs.endpointUrl, prefs.username, prefs.apiKey)
        } else null
    }

    private fun startBle() {
        val prefs = Prefs.get(this)
        bleManager = R02BleManager(
            context = this,
            deviceName = prefs.deviceName,
            pollIntervalMs = prefs.pollIntervalMs
        )

        // Collect BLE metrics
        lifecycleScope.launch {
            for (metric in bleManager.metricChannel) {
                persistMetric(metric)
            }
        }

        bleManager.startScan()
    }

    // -----------------------------------------------------------------------
    // Metric persistence (ring → buffer)
    // -----------------------------------------------------------------------

    private suspend fun persistMetric(metric: RingMetric) {
        val rows = mutableListOf<BufferedMetric>()
        val ts = metric.timestampMs

        if (metric.heartRateBpm != RingMetric.UNAVAILABLE) {
            rows += BufferedMetric(
                metricName = "grafit_heart_rate_bpm",
                labels = commonLabels,
                value = metric.heartRateBpm.toDouble(),
                timestampMs = ts
            )
        }
        if (metric.spO2Percent != RingMetric.UNAVAILABLE) {
            rows += BufferedMetric(
                metricName = "grafit_spo2_percent",
                labels = commonLabels,
                value = metric.spO2Percent.toDouble(),
                timestampMs = ts
            )
        }
        if (metric.steps != RingMetric.UNAVAILABLE) {
            rows += BufferedMetric(
                metricName = "grafit_steps_total",
                labels = commonLabels,
                value = metric.steps.toDouble(),
                timestampMs = ts
            )
        }
        if (metric.temperatureCentidegrees != RingMetric.UNAVAILABLE) {
            rows += BufferedMetric(
                metricName = "grafit_temperature_celsius",
                labels = commonLabels,
                value = metric.temperatureCentidegrees / 100.0,
                timestampMs = ts
            )
        }
        if (metric.batteryPercent != RingMetric.UNAVAILABLE) {
            rows += BufferedMetric(
                metricName = "grafit_battery_percent",
                labels = commonLabels,
                value = metric.batteryPercent.toDouble(),
                timestampMs = ts
            )
        }

        if (rows.isNotEmpty()) {
            db.metricDao().insertAll(rows)
            val maxRows = Prefs.get(this).bufferMaxRows
            db.metricDao().trimToSize(maxRows)
        }
    }

    // -----------------------------------------------------------------------
    // Flush loop (buffer → Grafana Cloud)
    // -----------------------------------------------------------------------

    private fun startFlushLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(Prefs.get(this@DataSyncService).flushIntervalMs)
                flush()
            }
        }
    }

    private suspend fun flush() {
        val w = writer ?: return
        val dao = db.metricDao()
        val batch = dao.getOldest(FLUSH_BATCH_SIZE)
        if (batch.isEmpty()) return

        val timeSeries = batch.map { row ->
            val extraLabels = parseLabels(row.labels)
            PrometheusTimeSeries(
                labels = buildList {
                    add(PrometheusLabel("__name__", row.metricName))
                    addAll(extraLabels)
                },
                samples = listOf(PrometheusSample(row.value, row.timestampMs))
            )
        }

        if (w.send(timeSeries)) {
            dao.deleteByIds(batch.map { it.id })
            Log.d(TAG, "Flushed ${batch.size} metrics to Grafana Cloud")
            updateNotification("Streaming – buffer: ${dao.count()} rows")
        } else {
            Log.w(TAG, "Flush failed – ${dao.count()} metrics buffered")
            updateNotification("Offline – buffered ${dao.count()} metrics")
        }
    }

    /** Converts "key1=val1,key2=val2" into [PrometheusLabel] list. */
    private fun parseLabels(raw: String): List<PrometheusLabel> =
        raw.split(',').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else PrometheusLabel(pair.substring(0, idx), pair.substring(idx + 1))
        }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Grafit sync", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "R02 ring data streaming" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ring_notification)
            .setContentTitle("Grafit")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "DataSyncService"
        private const val CHANNEL_ID = "grafit_sync"
        private const val NOTIFICATION_ID = 1001
        private const val FLUSH_BATCH_SIZE = 200

        fun start(context: Context) {
            val intent = Intent(context, DataSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DataSyncService::class.java))
        }
    }
}
