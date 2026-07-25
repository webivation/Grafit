package com.webivation.grafit.ring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hot holder for the most recent [RingMetric], bridging
 * [DataSyncService][com.webivation.grafit.service.DataSyncService] (producer)
 * and the UI layer (consumer) which otherwise have no direct reference to
 * each other.
 */
object LiveRingMetric {
    private val _latest = MutableStateFlow<RingMetric?>(null)
    val latest: StateFlow<RingMetric?> = _latest.asStateFlow()

    fun update(metric: RingMetric) {
        _latest.value = metric
    }
}
