package com.webivation.grafit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.webivation.grafit.databinding.ActivityMainBinding
import com.webivation.grafit.ring.RingMetric
import com.webivation.grafit.service.DataSyncService
import com.webivation.grafit.service.Prefs
import com.webivation.grafit.util.CrashLogger
import com.webivation.grafit.viewmodel.MainViewModel

/**
 * Main screen: shows live ring readings, buffer depth, streaming toggle,
 * and a link to Settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            observeViewModel()
            binding.fabToggleStream.setOnClickListener { toggleStreaming() }
            Log.i(TAG, "MainActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in MainActivity.onCreate()", e)
            CrashLogger.logException(this, e, TAG)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu", e)
            CrashLogger.logException(this, e, TAG)
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            return super.onOptionsItemSelected(item)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item", e)
            CrashLogger.logException(this, e, TAG)
            return false
        }
    }

    // -----------------------------------------------------------------------
    // ViewModel observers
    // -----------------------------------------------------------------------

    private fun observeViewModel() {
        try {
            vm.latestMetric.observe(this) { metric -> 
                try {
                    bindMetric(metric)
                } catch (e: Exception) {
                    Log.e(TAG, "Error binding metric", e)
                }
            }
            vm.bufferCount.observe(this) { count ->
                try {
                    binding.tvBufferCount.text = getString(R.string.label_buffer_count, count)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating buffer count", e)
                }
            }
            vm.isStreaming.observe(this) { streaming ->
                try {
                    binding.fabToggleStream.setImageResource(
                        if (streaming) R.drawable.ic_stop else R.drawable.ic_play
                    )
                    binding.tvStatus.text = getString(
                        if (streaming) R.string.status_streaming else R.string.status_stopped
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating streaming status", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up ViewModel observers", e)
            CrashLogger.logException(this, e, TAG)
        }
    }

    private fun bindMetric(m: RingMetric) {
        val na = getString(R.string.value_na)
        binding.tvHeartRate.text = if (m.heartRateBpm != RingMetric.UNAVAILABLE)
            getString(R.string.value_bpm, m.heartRateBpm) else na
        binding.tvSpO2.text = if (m.spO2Percent != RingMetric.UNAVAILABLE)
            getString(R.string.value_percent, m.spO2Percent) else na
        binding.tvSteps.text = if (m.steps != RingMetric.UNAVAILABLE)
            m.steps.toString() else na
        binding.tvTemperature.text = if (m.temperatureCentidegrees != RingMetric.UNAVAILABLE)
            getString(R.string.value_celsius, m.temperatureCentidegrees / 100.0) else na
        binding.tvBattery.text = if (m.batteryPercent != RingMetric.UNAVAILABLE)
            getString(R.string.value_percent, m.batteryPercent) else na
    }

    // -----------------------------------------------------------------------
    // Streaming toggle + permission handling
    // -----------------------------------------------------------------------

    private fun toggleStreaming() {
        try {
            if (vm.isStreaming.value == true) {
                DataSyncService.stop(this)
                vm.setStreaming(false)
            } else {
                requestPermissionsThenStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling stream", e)
            CrashLogger.logException(this, e, TAG)
            Toast.makeText(this, "Error toggling stream: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        try {
            val allGranted = results.values.all { it }
            if (allGranted) {
                startStreaming()
            } else {
                Toast.makeText(this, R.string.error_permissions_required, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in permission launcher", e)
            CrashLogger.logException(this, e, TAG)
        }
    }

    private fun requestPermissionsThenStart() {
        try {
            val prefs = Prefs.get(this)
            if (prefs.endpointUrl.isBlank() || prefs.username.isBlank() || prefs.apiKey.isBlank()) {
                Toast.makeText(this, R.string.error_configure_grafana, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }

            val required = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val missing = required.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isEmpty()) {
                startStreaming()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            CrashLogger.logException(this, e, TAG)
        }
    }

    private fun startStreaming() {
        try {
            DataSyncService.start(this)
            vm.setStreaming(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream", e)
            CrashLogger.logException(this, e, TAG)
            Toast.makeText(this, "Error starting stream: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
