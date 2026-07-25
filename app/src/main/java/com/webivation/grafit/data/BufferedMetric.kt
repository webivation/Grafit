package com.webivation.grafit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Prometheus time-series sample that has been buffered locally while the
 * network or Grafana Cloud endpoint is temporarily unavailable.
 *
 * Each row holds a single [value] for one named metric (identified by [metricName]
 * plus [labels]) at a specific [timestampMs].  The row is deleted once it has
 * been successfully flushed to Grafana Cloud.
 */
@Entity(tableName = "buffered_metrics")
data class BufferedMetric(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Prometheus metric name, e.g. "grafit_heart_rate_bpm". */
    val metricName: String,

    /**
     * Serialised label key=value pairs separated by ','.
     * Example: "device=R02,source=grafit"
     */
    val labels: String,

    val value: Double,

    /** Unix epoch milliseconds – used as the Prometheus sample timestamp. */
    val timestampMs: Long = System.currentTimeMillis(),

    /** Number of failed delivery attempts so far. */
    val retryCount: Int = 0
)
