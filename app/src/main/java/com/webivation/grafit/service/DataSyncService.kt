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
import com.webivation.grafit.health.HealthConnectSource
import com.webivation.grafit.health.HealthMetric
import com.webivation.grafit.health.LiveHealthMetric
import com.webivation.grafit.network.PrometheusLabel
import com.webivation.grafit.network.PrometheusRemoteWriter
import com.webivation.grafit.network.PrometheusSample
import com.webivation.grafit.network.PrometheusTimeSeries
import com.webivation.grafit.util.CrashLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  1. Periodically reads heart rate/steps from Health Connect (written there
 *     by the ring's companion app) via [HealthConnectSource].
 *  2. Converts new readings to Prometheus time-series.
 *  3. Tries to flush metrics to Grafana Cloud via [PrometheusRemoteWriter].
 *  4. On failure, persists metrics to the local Room buffer ([AppDatabase])
 *     and retries on the next flush cycle.
 *  5. Trims the buffer to [Prefs.bufferMaxRows] to cap disk usage.
 */
class DataSyncService : LifecycleService() {

    private lateinit var healthConnectSource: HealthConnectSource
    private lateinit var db: AppDatabase

    // Created lazily after reading SharedPreferences
    private var writer: PrometheusRemoteWriter? = null

    private var deviceLabel = "R02"
    /** Pre-computed once after [initFromPrefs] to avoid repeated string concatenation. */
    private var commonLabels = "device=$deviceLabel,source=grafit"

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Reading from Health Connect…"))

            db = AppDatabase.getInstance(this)
            initFromPrefs()
            healthConnectSource = HealthConnectSource(this)
            startHealthConnectPolling()
            startFlushLoop()
            Log.i(TAG, "DataSyncService created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in DataSyncService.onCreate()", e)
            CrashLogger.logException(this, e, TAG)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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

    // -----------------------------------------------------------------------
    // Health Connect polling
    // -----------------------------------------------------------------------

    private fun startHealthConnectPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    pollHealthConnect()
                    consecutiveErrors = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Error reading Health Connect (attempt $consecutiveErrors)", e)
                    CrashLogger.logException(this@DataSyncService, e, TAG)
                    if (consecutiveErrors >= MAX_METRIC_COLLECTION_ERRORS) {
                        Log.e(TAG, "Exiting Health Connect polling due to repeated failures")
                        break
                    }
                }
                delay(Prefs.get(this@DataSyncService).pollIntervalMs)
            }
        }
    }

    private suspend fun pollHealthConnect() {
        val prefs = Prefs.get(this)
        val token = prefs.healthConnectChangesToken
            ?: healthConnectSource.getInitialChangesToken().also { prefs.healthConnectChangesToken = it }

        val changes = try {
            healthConnectSource.readChanges(token)
        } catch (e: IllegalStateException) {
            // Token too old / invalidated by Health Connect - restart from a fresh token.
            Log.w(TAG, "Health Connect changes token expired, requesting a fresh one", e)
            val freshToken = healthConnectSource.getInitialChangesToken()
            prefs.healthConnectChangesToken = freshToken
            return
        }

        changes.metrics.lastOrNull { it.hasData() }?.let { LiveHealthMetric.update(it) }
        persistMetrics(changes.metrics)
        prefs.healthConnectChangesToken = changes.nextToken
    }

    // -----------------------------------------------------------------------
    // Metric persistence (Health Connect → buffer)
    // -----------------------------------------------------------------------

    private suspend fun persistMetrics(metrics: List<HealthMetric>) {
        val rows = mutableListOf<BufferedMetric>()

        for (metric in metrics) {
            val ts = metric.timestampMs
            if (metric.heartRateBpm != HealthMetric.UNAVAILABLE) {
                rows += BufferedMetric(
                    metricName = "grafit_heart_rate_bpm",
                    labels = commonLabels,
                    value = metric.heartRateBpm.toDouble(),
                    timestampMs = ts
                )
            }
            if (metric.steps != HealthMetric.UNAVAILABLE) {
                rows += BufferedMetric(
                    metricName = "grafit_steps_total",
                    labels = commonLabels,
                    value = metric.steps.toDouble(),
                    timestampMs = ts
                )
            }
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
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val baseInterval = Prefs.get(this@DataSyncService).flushIntervalMs
                    // Exponential backoff with base 2: delay = baseInterval * 2^min(consecutiveErrors, 16)
                    // Capped at MAX_BACKOFF_DELAY_MS (5 minutes) to prevent excessively long delays
                    val delayMs = if (consecutiveErrors == 0) {
                        baseInterval
                    } else {
                        val cappedErrors = minOf(consecutiveErrors, 16)  // Prevent overflow: 2^16 ~ 65k
                        minOf(baseInterval * (1L shl cappedErrors), MAX_BACKOFF_DELAY_MS)
                    }
                    delay(delayMs)
                    flush()
                    consecutiveErrors = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Flush loop error (attempt $consecutiveErrors)", e)
                    CrashLogger.logException(this@DataSyncService, e, TAG)
                    if (consecutiveErrors >= MAX_FLUSH_ERRORS) {
                        Log.e(TAG, "Exiting flush loop due to repeated failures")
                        break
                    }
                }
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
            val bufferCount = dao.count()
            updateNotification("Streaming – buffer: $bufferCount rows")
        } else {
            val bufferCount = dao.count()
            Log.w(TAG, "Flush failed – $bufferCount metrics buffered")
            updateNotification("Offline – buffered $bufferCount metrics")
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
            ).apply { description = "Health Connect data streaming" }
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
        private const val MAX_METRIC_COLLECTION_ERRORS = 5
        private const val MAX_FLUSH_ERRORS = 10
        private const val MAX_BACKOFF_DELAY_MS = 5 * 60 * 1000L  // 5 minutes

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
