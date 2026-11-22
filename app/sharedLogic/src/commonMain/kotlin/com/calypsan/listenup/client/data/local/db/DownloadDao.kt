package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.download.DownloadStopReason
import com.calypsan.listenup.client.download.DownloadTransport
import kotlinx.coroutines.flow.Flow

/**
 * Retry budget for a download: how many genuine failures a row may record. A FAILED row that has
 * spent it is terminal and is excluded from the every-startup re-enqueue (B10b) so exhausted
 * downloads stop churning. The Android worker retries against the same budget, counted on the row,
 * so a run the system stopped never spends it.
 */
internal const val MAX_DOWNLOAD_RETRIES = 3

/** The rows a book run may pick up: see [DownloadDao.nextForRun]. Bound to `:maxRetries`. */
private const val CLAIMABLE_FOR_RUN =
    "state = 'QUEUED' OR (state = 'PAUSED' AND (pauseReason IS NULL OR pauseReason IN ('NETWORK_LOST', 'INTERRUPTED'))) " +
        "OR (state = 'FAILED' AND retryCount < :maxRetries)"

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
     * silently re-enqueuing on every launch — the user re-triggers it manually. A row the person
     * paused is excluded for the same reason: it waits for them, not for the next launch.
     */
    @Query(
        "SELECT * FROM downloads WHERE state NOT IN ('COMPLETED', 'DELETED', 'CANCELLED') " +
            "AND NOT (state = 'FAILED' AND retryCount >= :maxRetries) " +
            "AND NOT (state = 'PAUSED' AND pauseReason IS 'PAUSED_BY_YOU') ORDER BY bookId, fileIndex",
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
     * Mark a file PAUSED for [reason] only if it is not already in a terminal state
     * (CANCELLED / DELETED / COMPLETED). Progress and the retry budget are left as they are.
     *
     * Closes the cancel/delete race (B7): a dying worker's late `NonCancellable` cleanup can call
     * this AFTER the user's CANCELLED (or DELETED) write has landed — the ordering between
     * WorkManager's `cancelAllWorkByTag().await()` and the worker coroutine's cleanup is undefined.
     * An unguarded `state = PAUSED` there would clobber the terminal state, and `getIncomplete()`
     * would silently resurrect a download the user explicitly cancelled or deleted. The `WHERE`
     * guard makes the terminal write win at the DB layer regardless of scheduling.
     *
     * A row the person paused keeps [DownloadPauseReason.PAUSED_BY_YOU]: the run they stopped reports its own stop
     * as it dies, and that must not turn their pause into a wait that resumes by itself.
     */
    @Query(
        "UPDATE downloads SET state = 'PAUSED', " +
            "pauseReason = CASE WHEN pauseReason = 'PAUSED_BY_YOU' AND state = 'PAUSED' THEN pauseReason ELSE :reason END " +
            "WHERE audioFileId = :audioFileId AND state NOT IN ('CANCELLED', 'DELETED', 'COMPLETED')",
    )
    suspend fun markPausedIfNotTerminal(
        audioFileId: String,
        reason: DownloadPauseReason,
    )

    /**
     * Put a file back in the queue for [reason] (null for none), unless it is already terminal
     * (CANCELLED / DELETED / COMPLETED). Progress and the retry budget are left as they are: a file
     * that goes back to the queue has not failed.
     */
    @Query(
        "UPDATE downloads SET state = 'QUEUED', pauseReason = :reason WHERE audioFileId = :audioFileId " +
            "AND state NOT IN ('CANCELLED', 'DELETED', 'COMPLETED')",
    )
    suspend fun markQueuedIfNotTerminal(
        audioFileId: String,
        reason: DownloadPauseReason?,
    )

    /**
     * Bytes are moving: a QUEUED (or already DOWNLOADING) file is DOWNLOADING, and whatever it was
     * waiting for is over. The first start time is kept. A no-op on any other state, so a late
     * callback cannot resurrect a row the person cancelled or deleted.
     */
    @Query(
        "UPDATE downloads SET state = 'DOWNLOADING', startedAt = COALESCE(startedAt, :startedAt), " +
            "pauseReason = NULL WHERE audioFileId = :audioFileId AND state IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun markTransferringIfLive(
        audioFileId: String,
        startedAt: Long,
    )

    /**
     * Claim a row for transfer: QUEUED, FAILED or PAUSED becomes DOWNLOADING. Returns the number of
     * rows changed — 1 means the caller owns the transfer, 0 means it must not start one.
     *
     * The guard is what stops a second transfer of a file that is already downloading or already
     * complete, and what keeps a row the user cancelled or deleted from being started at all.
     */
    @Query(
        "UPDATE downloads SET state = 'DOWNLOADING', startedAt = :startedAt WHERE audioFileId = :audioFileId " +
            "AND state IN ('QUEUED', 'FAILED', 'PAUSED')",
    )
    suspend fun claimForTransfer(
        audioFileId: String,
        startedAt: Long,
    ): Int

    /**
     * Record a failed transfer, unless the row has already reached a terminal state. A transfer the
     * user cancelled or deleted reports an error as it dies; that error must not undo their choice.
     */
    @Query(
        "UPDATE downloads SET state = 'FAILED', errorMessage = :error, retryCount = retryCount + 1 " +
            "WHERE audioFileId = :audioFileId AND state NOT IN ('CANCELLED', 'DELETED', 'COMPLETED')",
    )
    suspend fun markFailedIfNotTerminal(
        audioFileId: String,
        error: String,
    )

    /**
     * The next file of [bookId] a book run should transfer, by file order: queued, waiting on a pause the platform
     * resumes by itself, or failed with retries left. Null when the book has nothing left to transfer. A file the
     * person paused, or one waiting for sign-in, is never picked.
     */
    @Query(
        "SELECT * FROM downloads WHERE bookId = :bookId AND (" + CLAIMABLE_FOR_RUN + ") ORDER BY fileIndex LIMIT 1",
    )
    suspend fun nextForRun(
        bookId: String,
        maxRetries: Int,
    ): DownloadEntity?

    /**
     * Claim [audioFileId] for a book run: DOWNLOADING if it is still one [nextForRun] would pick. Returns 1 when the
     * run owns the transfer, 0 when the row moved on (paused, cancelled, completed) in the meantime.
     */
    @Query(
        "UPDATE downloads SET state = 'DOWNLOADING', startedAt = :startedAt WHERE audioFileId = :audioFileId AND (" +
            CLAIMABLE_FOR_RUN + ")",
    )
    suspend fun claimForRun(
        audioFileId: String,
        startedAt: Long,
        maxRetries: Int,
    ): Int

    /** Pause every unfinished file of [bookId] for the person: their pause wins over any wait. */
    @Query(
        "UPDATE downloads SET state = 'PAUSED', pauseReason = 'PAUSED_BY_YOU' WHERE bookId = :bookId " +
            "AND state IN ('QUEUED', 'DOWNLOADING', 'PAUSED')",
    )
    suspend fun pauseBookForUser(bookId: String): Int

    /**
     * Forget every file of [bookId] that is still on the device or on its way: CANCELLED with no local path. The
     * caller removes the files. Tombstones (DELETED) are left alone, so "explicitly deleted" still reads true.
     */
    @Query("UPDATE downloads SET state = 'CANCELLED', localPath = NULL WHERE bookId = :bookId AND state != 'DELETED'")
    suspend fun discardBook(bookId: String)

    /** How many books other than [bookId] have files waiting or moving: the queue behind the current download. */
    @Query(
        "SELECT COUNT(DISTINCT bookId) FROM downloads WHERE bookId != :bookId AND state IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun countOtherActiveBooks(bookId: String): Int

    /** The ids of books with at least one file a run could still transfer, in the order they were queued. */
    @Query(
        "SELECT bookId FROM downloads WHERE " + CLAIMABLE_FOR_RUN + " GROUP BY bookId ORDER BY MIN(queuedAt)",
    )
    suspend fun booksWithRunnableFiles(maxRetries: Int): List<String>

    /**
     * Record that a run stopped [audioFileId] before it finished: the mapped [reason], the platform's raw [code] (null
     * when it gave none), when, and which [transport] ran it. Counts the stop.
     */
    @Query(
        "UPDATE downloads SET stopReason = :reason, stopReasonCode = :code, stoppedAt = :at, " +
            "stopCount = stopCount + 1, transport = :transport WHERE audioFileId = :audioFileId",
    )
    suspend fun recordStop(
        audioFileId: String,
        reason: DownloadStopReason,
        code: Int?,
        at: Long,
        transport: DownloadTransport?,
    )

    /**
     * Record a genuine failure with its error [code], unless the row has already reached a terminal state: FAILED,
     * [message] shown, one retry spent. A transfer the person cancelled or deleted reports an error as it dies; that
     * error must not undo their choice.
     */
    @Query(
        "UPDATE downloads SET state = 'FAILED', errorMessage = :message, lastErrorCode = :code, " +
            "retryCount = retryCount + 1 WHERE audioFileId = :audioFileId " +
            "AND state NOT IN ('CANCELLED', 'DELETED', 'COMPLETED')",
    )
    suspend fun recordFailure(
        audioFileId: String,
        message: String,
        code: String,
    )

    /**
     * Move a live transfer between QUEUED (held, waiting for an allowed network) and DOWNLOADING.
     * A no-op on any other state, so a late callback cannot resurrect a finished row.
     */
    @Query(
        "UPDATE downloads SET state = :state, startedAt = :startedAt WHERE audioFileId = :audioFileId " +
            "AND state IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun updateStateIfTransferring(
        audioFileId: String,
        state: DownloadState,
        startedAt: Long? = null,
    )

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

    /**
     * Mark a row completed, unless the user cancelled or deleted it. A transfer that finishes after
     * that choice must not resurrect the row. Returns the number of rows changed: 0 means the file
     * landed after the cancel or delete and the caller still holds bytes nothing points at.
     */
    @Query(
        """
        UPDATE downloads SET
            state = :state,
            localPath = :localPath,
            completedAt = :completedAt,
            downloadedBytes = totalBytes
        WHERE audioFileId = :audioFileId AND state NOT IN ('CANCELLED', 'DELETED')
    """,
    )
    suspend fun markCompletedWithState(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
        state: DownloadState,
    ): Int

    suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): Int =
        markCompletedWithState(
            audioFileId = audioFileId,
            localPath = localPath,
            completedAt = completedAt,
            state = DownloadState.COMPLETED,
        )

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
