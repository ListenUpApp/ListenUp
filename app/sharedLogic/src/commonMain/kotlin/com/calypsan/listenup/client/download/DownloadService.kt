package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

private val downloadServiceLogger = KotlinLogging.logger {}

/**
 * Interface for download operations needed by PlaybackManager.
 *
 * This abstraction allows PlaybackManager to live in shared code while
 * the full download implementation remains platform-specific.
 *
 * Android: Implemented by DownloadManager (WorkManager-based)
 * iOS: AppleDownloadService — an NSURLSession *default* session, not a background one. It does not
 *   survive app termination; the "background" it used to claim here was never built.
 * Desktop and browser: NoDownloadsService (no-op)
 */
interface DownloadService {
    /**
     * Whether this backend can actually complete a download. No default — every implementation
     * states the fact explicitly, so a new backend that forgets to override this fails to
     * compile rather than silently inheriting the wrong answer. There are exactly three
     * implementations (Android's `DownloadManager`, iOS's `AppleDownloadService`, and
     * `NoDownloadsService` for Desktop and the browser), so the abstract declaration costs each
     * of them one line.
     *
     * [PlaybackPreparer.triggerBackgroundDownloadIfNeeded] asks this rather than inferring
     * download capability from `DeviceContext`'s form factor: those are different questions, and
     * conflating them is exactly the bug this property exists to prevent — a coarse-pointer
     * mobile browser reads as `DeviceType.Phone`/`Tablet` (which DOES support downloads as a form
     * factor) even though its `DownloadService` cannot finish one. `DeviceContext.supportsDownloads`
     * stays in play alongside this as the form-factor half of the gate (TV/Auto/Xr never want
     * downloads regardless of backend); this is the backend-capability half.
     */
    val supportsDownloads: Boolean

    /**
     * Get local file path for an audio file if downloaded.
     * Returns null if not downloaded or file missing.
     */
    suspend fun getLocalPath(audioFileId: String): String?

    /**
     * Check if user explicitly deleted downloads for this book.
     * Used to determine if we should auto-download on playback.
     */
    suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean

    /**
     * Trigger background download of a book's audio files.
     *
     * Returns [AppResult.Success] with one of:
     * - [DownloadOutcome.Started] — fresh enqueue.
     * - [DownloadOutcome.AlreadyDownloaded] — all files already complete; no work enqueued.
     * - [DownloadOutcome.InsufficientStorage] — pre-flight storage check failed; no work enqueued.
     *
     * Returns [AppResult.Failure] with [com.calypsan.listenup.api.error.DownloadError]
     * for unexpected errors (book not found, missing audio metadata, etc.).
     */
    suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome>

    /**
     * iOS-safe accessor: the [DownloadOutcome] or `null` on failure (folded in Kotlin). Use from
     * Swift — never `await` the `AppResult`-returning [downloadBook] (Swift Export bridge trap).
     */
    suspend fun downloadBookOrNull(bookId: BookId): DownloadOutcome? =
        downloadBook(
            bookId,
        ).valueOrNull { downloadServiceLogger.warn { "downloadBookOrNull: ${it.debugInfo ?: it.message}" } }

    /**
     * Cancel active download for a book.
     */
    suspend fun cancelDownload(bookId: BookId)

    /**
     * Delete downloaded files for a book.
     */
    suspend fun deleteDownload(bookId: BookId)

    /**
     * Delete every downloaded file and every download record in one sweep ("Delete All Downloads").
     *
     * Unlike iterating [deleteDownload] over the books known to the library, this reclaims *orphaned*
     * files and rows too — downloads whose book is no longer in the local library would otherwise be
     * invisible and unreclaimable. Platform impls wipe the audiobooks directory and the downloads
     * table; the default no-ops (desktop has no downloads).
     */
    suspend fun deleteAllDownloads() {
        // Default no-op — platforms without a download backend (desktop) have nothing to reclaim.
    }

    /**
     * Observe download status for a book as a Flow.
     */
    fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus>

    /**
     * Observe download status for all books, keyed by bookId. Reserved for cross-book UIs
     * (e.g., library list download badges) — no current consumers; the method exists on the
     * interface so callers don't need the platform-specific type when they arrive.
     */
    fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>>

    /**
     * Resume any incomplete downloads (e.g. after re-authentication or app restart).
     */
    suspend fun resumeIncompleteDownloads()
}
