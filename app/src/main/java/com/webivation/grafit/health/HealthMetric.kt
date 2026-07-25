package com.webivation.grafit.health

/**
 * A single measurement read from Health Connect (written there by the ring's
 * companion app).
 *
 * Fields not present in a given record carry a sentinel value of
 * [UNAVAILABLE] so the caller can filter them out before forwarding to
 * Prometheus.
 */
data class HealthMetric(
    /** Unix epoch milliseconds when the sample was recorded. */
    val timestampMs: Long = System.currentTimeMillis(),

    /** Heart rate in beats-per-minute, or [UNAVAILABLE]. */
    val heartRateBpm: Int = UNAVAILABLE,

    /** Step count for the covered interval, or [UNAVAILABLE]. */
    val steps: Int = UNAVAILABLE
) {
    companion object {
        const val UNAVAILABLE = Int.MIN_VALUE
    }

    /** True when at least one valid metric was decoded. */
    fun hasData(): Boolean = listOf(heartRateBpm, steps).any { it != UNAVAILABLE }
}
