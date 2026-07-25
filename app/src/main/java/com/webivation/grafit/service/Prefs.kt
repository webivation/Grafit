package com.webivation.grafit.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Typed wrapper around the app's [SharedPreferences] for all user-configurable
 * settings.  All reads return safe defaults so the app is functional before
 * the user opens [com.webivation.grafit.SettingsActivity].
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    // -----------------------------------------------------------------------
    // Grafana Cloud / Prometheus settings
    // -----------------------------------------------------------------------

    /**
     * Full URL of the Prometheus remote-write endpoint.
     *
     * Grafana Cloud format:
     *   https://prometheus-prod-XX-prod-us-central-0.grafana.net/api/prom/push
     */
    var endpointUrl: String
        get() = sp.getString(KEY_ENDPOINT_URL, "") ?: ""
        set(v) = sp.edit { putString(KEY_ENDPOINT_URL, v) }

    /** Grafana Cloud numeric user ID (HTTP basic-auth username). */
    var username: String
        get() = sp.getString(KEY_USERNAME, "") ?: ""
        set(v) = sp.edit { putString(KEY_USERNAME, v) }

    /** Grafana Cloud API key or password (HTTP basic-auth password). */
    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(v) = sp.edit { putString(KEY_API_KEY, v) }

    // -----------------------------------------------------------------------
    // BLE settings
    // -----------------------------------------------------------------------

    /** Advertised BLE device name to scan for (default "R02"). */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(v) = sp.edit { putString(KEY_DEVICE_NAME, v) }

    /** How frequently (ms) the ring is polled for new readings. */
    var pollIntervalMs: Long
        get() = getLongCompat(KEY_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS)
        set(v) = sp.edit { putString(KEY_POLL_INTERVAL_MS, v.toString()) }

    // -----------------------------------------------------------------------
    // Buffer / sync settings
    // -----------------------------------------------------------------------

    /** How frequently (ms) the local buffer is flushed to Grafana Cloud. */
    var flushIntervalMs: Long
        get() = getLongCompat(KEY_FLUSH_INTERVAL_MS, DEFAULT_FLUSH_INTERVAL_MS)
        set(v) = sp.edit { putString(KEY_FLUSH_INTERVAL_MS, v.toString()) }

    /** Maximum number of metric rows kept in the local buffer. */
    var bufferMaxRows: Int
        get() = getIntCompat(KEY_BUFFER_MAX_ROWS, DEFAULT_BUFFER_MAX_ROWS)
        set(v) = sp.edit { putString(KEY_BUFFER_MAX_ROWS, v.toString()) }

    private fun getLongCompat(key: String, default: Long): Long {
        val asString = try {
            sp.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }
        if (asString != null) return asString.toLongOrNull() ?: default

        return try {
            sp.getLong(key, default)
        } catch (_: ClassCastException) {
            when (val value = sp.all[key]) {
                is Long -> value
                is Int -> value.toLong()
                is Float -> value.toLong()
                is Double -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                is String -> value.toLongOrNull() ?: default
                else -> default
            }
        }
    }

    private fun getIntCompat(key: String, default: Int): Int {
        val asString = try {
            sp.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }
        if (asString != null) return asString.toIntOrNull() ?: default

        return try {
            sp.getInt(key, default)
        } catch (_: ClassCastException) {
            when (val value = sp.all[key]) {
                is Int -> value
                is Long -> value.toInt()
                is Float -> value.toInt()
                is Double -> value.toInt()
                is Short -> value.toInt()
                is Byte -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "grafit_prefs"

        const val KEY_ENDPOINT_URL      = "endpoint_url"
        const val KEY_USERNAME          = "username"
        const val KEY_API_KEY           = "api_key"
        const val KEY_DEVICE_NAME       = "device_name"
        const val KEY_POLL_INTERVAL_MS  = "poll_interval_ms"
        const val KEY_FLUSH_INTERVAL_MS = "flush_interval_ms"
        const val KEY_BUFFER_MAX_ROWS   = "buffer_max_rows"

        const val DEFAULT_DEVICE_NAME       = "R02"
        const val DEFAULT_POLL_INTERVAL_MS  = 5_000L
        const val DEFAULT_FLUSH_INTERVAL_MS = 15_000L
        const val DEFAULT_BUFFER_MAX_ROWS   = 10_000

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
