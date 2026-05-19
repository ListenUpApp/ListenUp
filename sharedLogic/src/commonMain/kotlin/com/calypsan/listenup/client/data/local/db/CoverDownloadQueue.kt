package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.flow.Flow

/**
 * Status of a cover download task in the persistent queue.
 */
enum class CoverDownloadStatus {
    /** Waiting to be processed. */
    PENDING,

    /** Currently downloading. */
    IN_PROGRESS,

    /** Successfully downloaded and saved to disk. */
    COMPLETED,

    /** Download failed — will be retried with backoff. */
    FAILED,
}

/**
 * Persistent cover download task.
 *
 * Tracks individual cover downloads so they survive app backgrounding,
 * crashes, and restarts. Part of the sync resilience architecture —
 * replaces the old fire-and-forget coroutine approach.
 */
@Entity(tableName = "cover_download_queue")
data class CoverDownloadTaskEntity(
    @PrimaryKey
    val bookId: BookId,
    val status: CoverDownloadStatus = CoverDownloadStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: Timestamp? = null,
    val error: String? = null,
    val createdAt: Timestamp = Timestamp.now(),
)

/**
 * DAO for the cover download queue.
 */
@Dao
interface CoverDownloadDao {
    /**
     * Enqueue cover downloads. Existing entries are ignored (IGNORE strategy)
     * so re-syncing doesn't reset in-progress or completed tasks.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(tasks: List<CoverDownloadTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(task: CoverDownloadTaskEntity)

    /**
     * Get the next batch of tasks to process.
     * Picks PENDING tasks first, then FAILED tasks that haven't exceeded max retries.
     * Orders by creation time so oldest tasks are processed first.
     */
    @Query(
        """
        SELECT * FROM cover_download_queue 
        WHERE status = 'PENDING' 
           OR (status = 'FAILED' AND attempts < :maxRetries)
        ORDER BY 
            CASE status WHEN 'PENDING' THEN 0 ELSE 1 END,
            createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getNextBatch(
        limit: Int = 5,
        maxRetries: Int = 5,
    ): List<CoverDownloadTaskEntity>

    @Query("UPDATE cover_download_queue SET status = :status WHERE bookId = :bookId")
    suspend fun updateStatus(
        bookId: BookId,
        status: CoverDownloadStatus,
    )

    @Query(
        """
        UPDATE cover_download_queue 
        SET status = 'FAILED', attempts = attempts + 1, lastAttemptAt = :now, error = :error 
        WHERE bookId = :bookId
        """,
    )
    suspend fun markFailed(
        bookId: BookId,
        error: String?,
        now: Timestamp = Timestamp.now(),
    )

    @Query(
        """
        UPDATE cover_download_queue 
        SET status = 'COMPLETED', lastAttemptAt = :now 
        WHERE bookId = :bookId
        """,
    )
    suspend fun markCompleted(
        bookId: BookId,
        now: Timestamp = Timestamp.now(),
    )

    @Query("UPDATE cover_download_queue SET status = 'IN_PROGRESS' WHERE bookId = :bookId")
    suspend fun markInProgress(bookId: BookId)

    /**
     * Reset any IN_PROGRESS tasks back to PENDING.
     * Called on app startup to recover from interrupted downloads.
     */
    @Query("UPDATE cover_download_queue SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgress()

    /**
     * Count of pending + failed (retryable) tasks.
     * Used for UI progress display.
     */
    @Query(
        """
        SELECT COUNT(*) FROM cover_download_queue 
        WHERE status = 'PENDING' OR (status = 'FAILED' AND attempts < 5)
        """,
    )
    fun observeRemainingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cover_download_queue WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cover_download_queue")
    fun observeTotalCount(): Flow<Int>

    /**
     * Remove completed tasks older than the given timestamp.
     * Housekeeping to prevent the queue from growing forever.
     */
    @Query("DELETE FROM cover_download_queue WHERE status = 'COMPLETED' AND lastAttemptAt < :before")
    suspend fun purgeCompleted(before: Timestamp)

    /**
     * Remove a specific task (e.g., when book is deleted).
     */
    @Query("DELETE FROM cover_download_queue WHERE bookId = :bookId")
    suspend fun remove(bookId: BookId)

    /**
     * Re-enqueue a cover for download (e.g., when cover changed on server).
     * Resets status to PENDING and clears attempts.
     */
    @Query(
        """
        UPDATE cover_download_queue 
        SET status = 'PENDING', attempts = 0, error = null, lastAttemptAt = null 
        WHERE bookId = :bookId
        """,
    )
    suspend fun reenqueue(bookId: BookId)
}
