package com.webivation.grafit

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.webivation.grafit.databinding.ActivitySettingsBinding
import com.webivation.grafit.service.Prefs

/**
 * Lightweight settings screen built with [PreferenceFragmentCompat] for
 * Grafana Cloud credentials, BLE device name, and sync tuning parameters.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "grafit_prefs"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Mask the API key field
            findPreference<EditTextPreference>(Prefs.KEY_API_KEY)?.setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            // Validate numeric fields on change
            listOf(Prefs.KEY_POLL_INTERVAL_MS, Prefs.KEY_FLUSH_INTERVAL_MS, Prefs.KEY_BUFFER_MAX_ROWS)
                .forEach { key ->
                    findPreference<EditTextPreference>(key)?.apply {
                        setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                        setOnPreferenceChangeListener { _, newValue ->
                            val n = (newValue as? String)?.toLongOrNull()
                            if (n == null || n <= 0) {
                                Toast.makeText(context, R.string.error_positive_number, Toast.LENGTH_SHORT).show()
                                false
                            } else true
                        }
                    }
                }
        }
    }
}
