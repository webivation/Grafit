package com.webivation.grafit.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.webivation.grafit.data.AppDatabase
import com.webivation.grafit.ring.RingMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Latest ring readings for display
    private val _latestMetric = MutableLiveData<RingMetric>()
    val latestMetric: LiveData<RingMetric> = _latestMetric

    private val _bufferCount = MutableLiveData(0)
    val bufferCount: LiveData<Int> = _bufferCount

    private val _isStreaming = MutableLiveData(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    init {
        startBufferMonitor()
    }

    fun setStreaming(running: Boolean) {
        _isStreaming.value = running
    }

    fun postLatestMetric(metric: RingMetric) {
        _latestMetric.postValue(metric)
    }

    /** Polls the DB row-count every [POLL_INTERVAL_MS] so the UI stays current. */
    private fun startBufferMonitor() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val count = runCatching {
                    AppDatabase.getInstance(application).metricDao().count()
                }
                    .onFailure { Log.e(TAG, "Failed to read buffered metric count", it) }
                    .getOrDefault(0)
                _bufferCount.postValue(count)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
