package com.webivation.grafit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: BufferedMetric): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<BufferedMetric>)

    /** Returns the oldest [limit] rows so they are flushed in FIFO order. */
    @Query("SELECT * FROM buffered_metrics ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun getOldest(limit: Int = 500): List<BufferedMetric>

    @Query("DELETE FROM buffered_metrics WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM buffered_metrics")
    suspend fun count(): Int

    /**
     * Removes the oldest rows when the buffer exceeds [maxRows], keeping
     * the newest data in case of a prolonged outage.
     */
    @Query(
        """
        WITH counted AS (SELECT COUNT(*) as cnt FROM buffered_metrics)
        DELETE FROM buffered_metrics
        WHERE id IN (
            SELECT id FROM buffered_metrics
            ORDER BY timestampMs ASC
            LIMIT CASE WHEN (SELECT cnt FROM counted) > :maxRows
                       THEN (SELECT cnt FROM counted) - :maxRows
                       ELSE 0 END
        )
        """
    )
    suspend fun trimToSize(maxRows: Int)

    @Query("SELECT COUNT(*) FROM buffered_metrics")
    fun countSync(): Int
}
