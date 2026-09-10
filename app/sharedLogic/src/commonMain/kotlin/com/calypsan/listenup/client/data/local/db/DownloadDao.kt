package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Retry budget for a download. A FAILED row that has burned this many attempts is terminal and is
 * excluded from the every-startup re-enqueue (B10b) so exhausted downloads stop churning. Kept in
 * lock-step with the Android `DownloadWorker`'s `MAX_RETRIES`.
 */
internal const val MAX_DOWNLOAD_RETRIES = 3

@Dao
internal interface DownloadDao {
    // Observe
    @Query("SELECT * FROM downloads WHERE bookId = :bookId ORDER BY fileIndex")
    fun observeForBook(bookId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY bookId, fileIndex")
    fun observeAll(): Flow<List<DownloadEntity>>

    // Query
    @Query("SELECT * FROM downloads WHERE bookId = :bookId ORDER BY fileIndex")
    suspend fun getForBook(bookId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE audioFileId = :audioFileId")
    suspend fun getByAudioFileId(audioFileId: String): DownloadEntity?

    /**
     * Get all downloads not in COMPLETED, DELETED, or CANCELLED state, EXCLUDING FAILED rows that
     * have exhausted their retry budget ([maxRetries]).
     *
     * Used to find stalled/interrupted downloads for resume. CANCELLED is excluded so that
     * resumeIncompleteDownloads does not silently restart a user-cancelled download on app start;
     * exhausted-FAILED is excluded (B10b) so a download that already burned its retries stops
     * silently re-enqueuing on every launch — the user re-triggers it manually.
     */
    @Query(
        "SELECT * FROM downloads WHERE state NOT IN ('COMPLETED', 'DELETED', 'CANCELLED') " +
            "AND NOT (state = 'FAILED' AND retryCount >= :maxRetries) ORDER BY bookId, fileIndex",
    )
    suspend fun getIncompleteWithin(maxRetries: Int): List<DownloadEntity>

    /** [getIncompleteWithin] with the canonical [MAX_DOWNLOAD_RETRIES] budget. */
    suspend fun getIncomplete(): List<DownloadEntity> = getIncompleteWithin(MAX_DOWNLOAD_RETRIES)

    /**
     * Get local path for a completed download.
     */
    @Query("SELECT localPath FROM downloads WHERE audioFileId = :audioFileId AND state = 'COMPLETED'")
    suspend fun getLocalPath(audioFileId: String): String?

    /**
     * Batched [getLocalPath]: one row per COMPLETED download among [audioFileIds], in a single
     * round trip instead of N. Same completeness predicate as [getLocalPath] (`state = 'COMPLETED'`)
     * — an id with no COMPLETED row is simply absent from the result, not returned as a null row.
     */
    @Query("SELECT audioFileId, localPath FROM downloads WHERE audioFileId IN (:audioFileIds) AND state = 'COMPLETED'")
    suspend fun getLocalPaths(audioFileIds: List<String>): List<AudioFileLocalPath>

    // Insert
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)

    // Update state
    @Query("UPDATE downloads SET state = :state, startedAt = :startedAt WHERE audioFileId = :audioFileId")
    suspend fun updateState(
        audioFileId: String,
        state: DownloadState,
        startedAt: Long? = null,
    )

    /**
     * Mark a file PAUSED only if it is not already in a terminal state
     * (CANCELLED / DELETED / COMPLETED).
     *
     * Closes the cancel/delete race (B7): a dying worker's late `NonCancellable` cleanup can call
     * this AFTER the user's CANCELLED (or DELETED) write has landed — the ordering between
     * WorkManager's `cancelAllWorkByTag().await()` and the worker coroutine's cleanup is undefined.
     * An unguarded `state = PAUSED` there would clobber the terminal state, and `getIncomplete()`
     * would silently resurrect a download the user explicitly cancelled or deleted. The `WHERE`
     * guard makes the terminal write win at the DB layer regardless of scheduling.
     */
    @Query(
        "UPDATE downloads SET state = 'PAUSED' WHERE audioFileId = :audioFileId " +
            "AND state NOT IN ('CANCELLED', 'DELETED', 'COMPLETED')",
    )
    suspend fun markPausedIfNotTerminal(audioFileId: String)

    @Query("UPDATE downloads SET state = :newState WHERE bookId = :bookId AND state != :excludeState")
    suspend fun updateStateForBookExcluding(
        bookId: String,
        newState: DownloadState,
        excludeState: DownloadState,
    )

    suspend fun updateStateForBook(
        bookId: String,
        state: DownloadState,
    ) = updateStateForBookExcluding(bookId, state, DownloadState.COMPLETED)

    // Update progress
    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total WHERE audioFileId = :audioFileId")
    suspend fun updateProgress(
        audioFileId: String,
        downloaded: Long,
        total: Long,
    )

    // Mark completed
    @Query(
        """
        UPDATE downloads SET
            state = :state,
            localPath = :localPath,
            completedAt = :completedAt,
            downloadedBytes = totalBytes
        WHERE audioFileId = :audioFileId
    """,
    )
    suspend fun markCompletedWithState(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
        state: DownloadState,
    )

    suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ) = markCompletedWithState(audioFileId, localPath, completedAt, DownloadState.COMPLETED)

    // Mark error
    @Query(
        """
        UPDATE downloads SET
            state = :state,
            errorMessage = :error,
            retryCount = retryCount + 1
        WHERE audioFileId = :audioFileId
    """,
    )
    suspend fun updateErrorWithState(
        audioFileId: String,
        error: String,
        state: DownloadState,
    )

    suspend fun updateError(
        audioFileId: String,
        error: String,
    ) = updateErrorWithState(audioFileId, error, DownloadState.FAILED)

    /**
     * Mark all files for a book as DELETED.
     * Used when user explicitly deletes a download - keeps records for tracking.
     */
    @Query("UPDATE downloads SET state = 'DELETED', localPath = NULL WHERE bookId = :bookId")
    suspend fun markDeletedForBook(bookId: String)

    /**
     * Check if a book has any DELETED records (user explicitly deleted).
     * Used to determine if we should auto-download on playback.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE bookId = :bookId AND state = 'DELETED')")
    suspend fun hasDeletedRecords(bookId: String): Boolean

    // Delete
    @Query("DELETE FROM downloads WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /**
     * Delete only the DELETED-tombstone rows for a book.
     *
     * Used post-playback-completion to clear the "user explicitly deleted" markers so the book can
     * auto-download again on a future listen — without touching COMPLETED rows, whose local files
     * must survive so the offline copy stays playable (never-stranded).
     */
    @Query("DELETE FROM downloads WHERE bookId = :bookId AND state = 'DELETED'")
    suspend fun deleteDeletedRecordsForBook(bookId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}

/**
 * Row shape for [DownloadDao.getLocalPaths]: one (audioFileId, localPath) pair per COMPLETED
 * download among the requested ids.
 */
internal data class AudioFileLocalPath(
    val audioFileId: String,
    val localPath: String?,
)
