package com.webivation.grafit.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends Prometheus Remote Write payloads to a Grafana Cloud (or any
 * Prometheus-compatible) endpoint using HTTP basic authentication.
 *
 * Thread-safe: the underlying [OkHttpClient] handles concurrent calls.
 */
class PrometheusRemoteWriter(
    /** Full URL to the Prometheus remote-write endpoint.
     *  Grafana Cloud example: https://prometheus-prod-XX-prod-eu-west-0.grafana.net/api/prom/push */
    private val endpointUrl: String,
    /** Grafana Cloud numeric user ID (used as the HTTP basic-auth username). */
    private val username: String,
    /** Grafana Cloud API key or password. */
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [timeSeries] synchronously.
     *
     * @return `true` on HTTP 204 / 200, `false` on any error so the caller
     *         can keep the data in the local buffer for a retry.
     */
    fun send(timeSeries: List<PrometheusTimeSeries>): Boolean {
        if (timeSeries.isEmpty()) return true

        val payload = try {
            PrometheusEncoder.encode(timeSeries)
        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed", e)
            return false
        }

        val body = payload.toRequestBody(CONTENT_TYPE)
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Encoding", "snappy")
            .addHeader("X-Prometheus-Remote-Write-Version", "0.1.0")
            .addHeader("User-Agent", "grafit/1.0")
            .addHeader("Authorization", okhttp3.Credentials.basic(username, apiKey))
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                if (!ok) {
                    Log.w(TAG, "Remote write HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
                ok
            }
        } catch (e: IOException) {
            Log.w(TAG, "Remote write I/O error: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "PrometheusWriter"
        private val CONTENT_TYPE = "application/x-protobuf".toMediaType()
    }
}
