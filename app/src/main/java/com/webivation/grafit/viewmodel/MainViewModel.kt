package com.webivation.grafit.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.webivation.grafit.data.AppDatabase
import com.webivation.grafit.data.MetricDao
import com.webivation.grafit.health.HealthMetric
import com.webivation.grafit.health.LiveHealthMetric
import com.webivation.grafit.util.CrashLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Latest Health Connect readings for display
    private val _latestMetric = MutableLiveData<HealthMetric>()
    val latestMetric: LiveData<HealthMetric> = _latestMetric

    private val _bufferCount = MutableLiveData(0)
    val bufferCount: LiveData<Int> = _bufferCount

    private val _isStreaming = MutableLiveData(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    init {
        try {
            startBufferMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing buffer monitor", e)
            CrashLogger.logException(getApplication(), e, TAG)
        }
        try {
            observeLiveMetric()
        } catch (e: Exception) {
            Log.e(TAG, "Error observing live ring metric", e)
            CrashLogger.logException(getApplication(), e, TAG)
        }
    }

    /** Mirrors [LiveHealthMetric], the latest Health Connect reading, onto the UI-facing LiveData. */
    private fun observeLiveMetric() {
        viewModelScope.launch {
            LiveHealthMetric.latest.collect { metric ->
                if (metric != null) postLatestMetric(metric)
            }
        }
    }

    fun setStreaming(running: Boolean) {
        _isStreaming.value = running
    }

    fun postLatestMetric(metric: HealthMetric) {
        _latestMetric.postValue(metric)
    }

    /** Polls the DB row-count every [POLL_INTERVAL_MS] so the UI stays current. */
    private fun startBufferMonitor() {
        viewModelScope.launch(Dispatchers.IO) {
            var dao: MetricDao? = null
            while (isActive) {
                val count = try {
                    val currentDao = dao ?: AppDatabase.getInstance(getApplication()).metricDao().also {
                        dao = it
                    }
                    currentDao.count()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dao = null
                    Log.e(TAG, "Failed to read buffered metric count", e)
                    0
                }
                _bufferCount.postValue(count)
                try {
                    delay(POLL_INTERVAL_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in buffer monitor delay", e)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
