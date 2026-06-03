package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import kotlinx.coroutines.flow.Flow

/**
 * Single seam for download state and orchestration. All writes to the download DAO route through
 * this repository (Sync Engine Rule 5 compliance). Aggregation lives here so all platforms share
 * the same state-machine reducer.
 *
 * Per W8 Phase B (handoff design § "Expand `DownloadRepository` interface"). Replaces:
 * - The narrow read-only interface that previously lived here (limited to `observeDownloadedBooks`
 *   and `deleteForBook`, used by `StorageViewModel`).
 * - Direct `DownloadDao` access from `DownloadWorker` and `DownloadManager` (those callers will
 *   be migrated to route through this interface in Phase B Tasks 7 and 8).
 *
 * Cross-phase carveout: methods marked **(Phase B alias)** below have placeholder implementations
 * until Phase D adds the corresponding `DownloadState` enum entries (`CANCELLED`,
 * `WAITING_FOR_SERVER`). The alias preserves the interface so Phases C and D don't reshape it.
 */
interface DownloadRepository {
    // --- Reads ---

    /** Observe the raw entities for a book (most callers should prefer [observeBookStatus]). */
    fun observeForBook(bookId: BookId): Flow<List<DownloadEntity>>

    /** Observe all download entities across all books. */
    fun observeAll(): Flow<List<DownloadEntity>>

    /** Observe the aggregated [BookDownloadStatus] for a single book. */
    fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus>

    /** Observe aggregated statuses keyed by bookId for cross-book UIs (e.g., library list). */
    fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>>

    /** Observe completed-and-known-to-the-book-repository downloads as domain summaries. */
    fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>>

    /** Get local file path for an audio file if downloaded; null otherwise. */
    suspend fun getLocalPath(audioFileId: String): String?

    /** Get the current [DownloadState] for an audio file, or null if no row exists. */
    suspend fun getStateForAudioFile(audioFileId: String): DownloadState?

    // --- State-transition writes (Sync Engine Rule 5 enforcement point) ---

    suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit>

    suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): AppResult<Unit>

    suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): AppResult<Unit>

    suspend fun markPaused(audioFileId: String): AppResult<Unit>

    /**
     * Mark the file as cancelled by user action. Distinct from [markPaused] (system-pause)
     * so the SSE handler can recognize and silently drop late `transcode.complete` events for
     * cancelled jobs.
     */
    suspend fun markCancelled(audioFileId: String): AppResult<Unit>

    suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Unit>

    /**
     * Mark the file as awaiting server transcoding. Persists [transcodeJobId] so the SSE-reconnect
     * recheck path can re-issue preparePlayback if the `transcode.complete` event was missed
     * during disconnect.
     */
    suspend fun markWaitingForServer(
        audioFileId: String,
        transcodeJobId: String,
    ): AppResult<Unit>

    // --- Orchestration ---

    /**
     * Pre-flight check + DB insert + worker enqueue for a book's audio files.
     *
     * **(Phase C/D scope)** — Phase B keeps orchestration on the `DownloadService` impls
     * (`DownloadManager` for Android, `AppleDownloadService` for iOS). This method throws
     * `NotImplementedError` until Phase C/D moves the platform code onto the repository.
     */
    suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome>

    /**
     * Cancel all in-flight downloads for a book. For WAITING_FOR_SERVER rows, calls
     * [com.calypsan.listenup.client.data.remote.PlaybackApiContract.cancelTranscode] so the
     * server stops the transcode job (Q8 B1: cancel-tells-server). All non-terminal rows
     * transition to [DownloadState.CANCELLED].
     */
    suspend fun cancelForBook(bookId: BookId): AppResult<Unit>

    /** Delete all download records for a book (used post-playback completion). */
    suspend fun deleteForBook(bookId: String)

    /**
     * Re-enqueue a single audio file's download. Called by the SSE `transcode.complete` handler
     * by the future download sync domain. Silently drops if the
     * row is CANCELLED or COMPLETED (late event tolerance).
     */
    suspend fun resumeForAudioFile(audioFileId: String): AppResult<Unit>

    /**
     * App-startup recovery: 24h backstop for stale WAITING_FOR_SERVER rows (mark as
     * [com.calypsan.listenup.api.error.DownloadError.TranscodeTimeout]) + re-enqueue
     * any other incomplete downloads via the platform [com.calypsan.listenup.client.download.DownloadEnqueuer].
     * Existing `DownloadManager.resumeIncompleteDownloads` (Android) remains the primary app-startup
     * hook; this method is for parity. Phase E may consolidate.
     */
    suspend fun resumeIncompleteDownloads(): AppResult<Unit>

    /**
     * SSE-reconnect hook: re-issue `preparePlayback` for any rows in WAITING_FOR_SERVER to catch
     * transcodes that completed during disconnect. Ready ones → [resumeForAudioFile]. Lost-job
     * ones → [markFailed] with [com.calypsan.listenup.api.error.DownloadError.TranscodeTimeout].
     * Still-transcoding ones left alone.
     */
    suspend fun recheckWaitingForServer(): AppResult<Unit>
}
