package com.webivation.grafit

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.webivation.grafit.databinding.ActivitySettingsBinding
import com.webivation.grafit.service.Prefs
import com.webivation.grafit.util.CrashLogger

/**
 * Lightweight settings screen built with [PreferenceFragmentCompat] for
 * Grafana Cloud credentials, BLE device name, and sync tuning parameters.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)

            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
            Log.i(TAG, "SettingsActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in SettingsActivity.onCreate()", e)
            CrashLogger.logException(this, e, TAG)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        try {
            onBackPressedDispatcher.onBackPressed()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating up", e)
            return super.onSupportNavigateUp()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            try {
                preferenceManager.sharedPreferencesName = "grafit_prefs"
                setPreferencesFromResource(R.xml.preferences, rootKey)

                // Mask the API key field
                try {
                    findPreference<EditTextPreference>(Prefs.KEY_API_KEY)?.setOnBindEditTextListener { et ->
                        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error setting up API key field", e)
                }

                // Validate numeric fields on change
                try {
                    listOf(Prefs.KEY_POLL_INTERVAL_MS, Prefs.KEY_FLUSH_INTERVAL_MS, Prefs.KEY_BUFFER_MAX_ROWS)
                        .forEach { key ->
                            findPreference<EditTextPreference>(key)?.apply {
                                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                                setOnPreferenceChangeListener { _, newValue ->
                                    try {
                                        val n = (newValue as? String)?.toLongOrNull()
                                        if (n == null || n <= 0) {
                                            Toast.makeText(context, R.string.error_positive_number, Toast.LENGTH_SHORT).show()
                                            false
                                        } else true
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error validating preference", e)
                                        false
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "Error setting up numeric fields", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCreatePreferences", e)
                context?.let { CrashLogger.logException(it, e, TAG) }
            }
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
