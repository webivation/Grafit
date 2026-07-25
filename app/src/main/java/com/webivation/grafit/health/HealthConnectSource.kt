package com.webivation.grafit.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest

/** Result of one [HealthConnectSource.readChanges] call. */
data class HealthConnectChanges(
    val metrics: List<HealthMetric>,
    /** Persist and pass back into the next [HealthConnectSource.readChanges] call. */
    val nextToken: String
)

/**
 * Reads heart rate and step data that the ring's companion app has already
 * written to Android Health Connect. Grafit never talks to the ring directly —
 * see memory/commit history for why the direct BLE protocol was abandoned.
 *
 * Uses the Changes API rather than a sliding time-range read: companion apps
 * like QRing can backfill records stamped with data timestamps well in the
 * past, which a "read everything since last poll's wall-clock time" window
 * would silently miss. The Changes API tracks insertions regardless of what
 * timestamp the record itself carries.
 */
class HealthConnectSource(context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    /** Call once, before the first [readChanges], and persist the result. */
    suspend fun getInitialChangesToken(): String =
        client.getChangesToken(ChangesTokenRequest(RECORD_TYPES))

    /** Reads all changes since [token]. Always persist [HealthConnectChanges.nextToken]. */
    suspend fun readChanges(token: String): HealthConnectChanges {
        val metrics = mutableListOf<HealthMetric>()
        var currentToken = token
        while (true) {
            val response = client.getChanges(currentToken)
            for (change in response.changes) {
                if (change !is UpsertionChange) continue
                when (val record = change.record) {
                    is HeartRateRecord -> for (sample in record.samples) {
                        metrics += HealthMetric(
                            timestampMs = sample.time.toEpochMilli(),
                            heartRateBpm = sample.beatsPerMinute.toInt()
                        )
                    }
                    is StepsRecord -> metrics += HealthMetric(
                        timestampMs = record.endTime.toEpochMilli(),
                        steps = record.count.toInt()
                    )
                }
            }
            currentToken = response.nextChangesToken
            if (!response.hasMore) break
        }
        return HealthConnectChanges(metrics.sortedBy { it.timestampMs }, currentToken)
    }

    companion object {
        private val RECORD_TYPES = setOf(HeartRateRecord::class, StepsRecord::class)

        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()

        suspend fun hasAllPermissions(context: Context): Boolean {
            val granted = HealthConnectClient.getOrCreate(context)
                .permissionController.getGrantedPermissions()
            return granted.containsAll(PERMISSIONS)
        }
    }
}
