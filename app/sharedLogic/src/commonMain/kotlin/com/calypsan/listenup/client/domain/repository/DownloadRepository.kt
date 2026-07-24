@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Download
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import kotlinx.coroutines.flow.Flow

/**
 * Single seam for download state and orchestration. All writes to the download DAO route through
 * this repository (Sync Engine Rule 5 compliance). Aggregation lives here so all platforms share
 * the same state-machine reducer.
 */
interface DownloadRepository {
    // --- Reads ---

    /** Observe download domain objects for a book (most callers should prefer [observeBookStatus]). */
    fun observeForBook(bookId: BookId): Flow<List<Download>>

    /** Observe all downloads across all books. */
    fun observeAll(): Flow<List<Download>>

    /** Observe the aggregated [BookDownloadStatus] for a single book. */
    fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus>

    /** Observe aggregated statuses keyed by bookId for cross-book UIs (e.g., library list). */
    fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>>

    /** Observe completed-and-known-to-the-book-repository downloads as domain summaries. */
    fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>>

    /** Get local file path for an audio file if downloaded; null otherwise. */
    suspend fun getLocalPath(audioFileId: String): String?

    /** Get the current [DownloadStatus] for an audio file, or null if no row exists. */
    suspend fun getStateForAudioFile(audioFileId: String): DownloadStatus?

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

    /** Mark the file as cancelled by user action. Distinct from [markPaused] (system-pause). */
    suspend fun markCancelled(audioFileId: String): AppResult<Unit>

    suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
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
     * Cancel all in-flight downloads for a book. All non-terminal rows transition to
     * [DownloadStatus.CANCELLED].
     */
    suspend fun cancelForBook(bookId: BookId): AppResult<Unit>

    /** Delete all download records for a book. */
    suspend fun deleteForBook(bookId: String)

    /**
     * Delete only the [DownloadStatus.DELETED]-tombstone rows for a book (used post-playback
     * completion). COMPLETED rows and their local files are preserved so a finished book's offline
     * copy stays playable — clearing the tombstones only re-enables auto-download on a future listen.
     */
    suspend fun deleteDeletedRecordsForBook(bookId: String)

    /**
     * App-startup recovery: re-enqueue any incomplete downloads via the platform
     * [com.calypsan.listenup.client.download.DownloadEnqueuer].
     * Existing `DownloadManager.resumeIncompleteDownloads` (Android) remains the primary app-startup
     * hook; this method is for parity. Phase E may consolidate.
     */
    suspend fun resumeIncompleteDownloads(): AppResult<Unit>
}
