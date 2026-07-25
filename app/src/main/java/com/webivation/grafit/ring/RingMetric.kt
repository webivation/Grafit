package com.webivation.grafit.ring

/**
 * A single measurement collected from the R02 ring.
 *
 * Fields that are not supported / not yet received from the ring carry a
 * sentinel value of [UNAVAILABLE] so the caller can filter them out before
 * forwarding to Prometheus.
 */
data class RingMetric(
    /** Unix epoch milliseconds when the sample was recorded on the phone. */
    val timestampMs: Long = System.currentTimeMillis(),

    /** Heart rate in beats-per-minute, or [UNAVAILABLE]. */
    val heartRateBpm: Int = UNAVAILABLE,

    /** Blood oxygen saturation in percent (0–100), or [UNAVAILABLE]. */
    val spO2Percent: Int = UNAVAILABLE,

    /** Step count since last sync, or [UNAVAILABLE]. */
    val steps: Int = UNAVAILABLE,

    /** Skin / body temperature in °C × 100 (e.g. 3698 = 36.98 °C),
     *  or [UNAVAILABLE]. */
    val temperatureCentidegrees: Int = UNAVAILABLE,

    /** Battery level in percent (0–100), or [UNAVAILABLE]. */
    val batteryPercent: Int = UNAVAILABLE
) {
    companion object {
        const val UNAVAILABLE = Int.MIN_VALUE
    }

    /** True when at least one valid metric was decoded. */
    fun hasData(): Boolean = listOf(
        heartRateBpm, spO2Percent, steps, temperatureCentidegrees, batteryPercent
    ).any { it != UNAVAILABLE }
}
