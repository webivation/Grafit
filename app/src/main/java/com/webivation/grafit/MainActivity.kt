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
import androidx.lifecycle.lifecycleScope
import com.webivation.grafit.databinding.ActivityMainBinding
import com.webivation.grafit.health.HealthConnectSource
import com.webivation.grafit.health.HealthMetric
import com.webivation.grafit.service.DataSyncService
import com.webivation.grafit.service.Prefs
import com.webivation.grafit.util.CrashLogger
import com.webivation.grafit.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Main screen: shows the latest Health Connect readings, buffer depth,
 * streaming toggle, and a link to Settings.
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
            binding.fabSettings.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
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

    private fun bindMetric(m: HealthMetric) {
        val na = getString(R.string.value_na)
        binding.tvHeartRate.text = if (m.heartRateBpm != HealthMetric.UNAVAILABLE)
            getString(R.string.value_bpm, m.heartRateBpm) else na
        binding.tvSteps.text = if (m.steps != HealthMetric.UNAVAILABLE)
            m.steps.toString() else na
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        try {
            if (results.values.all { it }) {
                requestHealthConnectPermissionsThenStart()
            } else {
                Toast.makeText(this, R.string.error_permissions_required, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in permission launcher", e)
            CrashLogger.logException(this, e, TAG)
        }
    }

    private val healthConnectPermissionLauncher = registerForActivityResult(
        HealthConnectSource.permissionRequestContract()
    ) { granted ->
        try {
            if (granted.containsAll(HealthConnectSource.PERMISSIONS)) {
                startStreaming()
            } else {
                Toast.makeText(this, R.string.error_health_connect_permissions_required, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Health Connect permission launcher", e)
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

            if (!HealthConnectSource.isAvailable(this)) {
                Toast.makeText(this, R.string.error_health_connect_unavailable, Toast.LENGTH_LONG).show()
                return
            }

            val required = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val missing = required.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isEmpty()) {
                requestHealthConnectPermissionsThenStart()
            } else {
                notificationPermissionLauncher.launch(missing.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            CrashLogger.logException(this, e, TAG)
        }
    }

    private fun requestHealthConnectPermissionsThenStart() {
        lifecycleScope.launch {
            try {
                if (HealthConnectSource.hasAllPermissions(this@MainActivity)) {
                    startStreaming()
                } else {
                    healthConnectPermissionLauncher.launch(HealthConnectSource.PERMISSIONS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking Health Connect permissions", e)
                CrashLogger.logException(this@MainActivity, e, TAG)
            }
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
