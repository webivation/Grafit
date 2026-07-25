package com.webivation.grafit.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hot holder for the most recent [HealthMetric], bridging
 * [DataSyncService][com.webivation.grafit.service.DataSyncService] (producer)
 * and the UI layer (consumer) which otherwise have no direct reference to
 * each other.
 */
object LiveHealthMetric {
    private val _latest = MutableStateFlow<HealthMetric?>(null)
    val latest: StateFlow<HealthMetric?> = _latest.asStateFlow()

    fun update(metric: HealthMetric) {
        _latest.value = metric
    }
}
