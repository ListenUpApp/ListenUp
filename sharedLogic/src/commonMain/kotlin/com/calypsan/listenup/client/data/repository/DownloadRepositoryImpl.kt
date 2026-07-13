package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Download
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.download.DownloadEnqueuer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single seam for download state + aggregation. All download writes go through this class
 * (Sync Engine Rule 5). Aggregation reducer lives here so platforms share the same state-machine.
 */
internal class DownloadRepositoryImpl(
    private val downloadDao: DownloadDao,
    private val bookRepository: BookRepository,
    private val enqueuer: DownloadEnqueuer,
) : DownloadRepository {
    // --- Reads ---

    override fun observeForBook(bookId: BookId): Flow<List<Download>> =
        downloadDao.observeForBook(bookId.value).map { entities -> entities.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Download>> =
        downloadDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        downloadDao.observeForBook(bookId.value).map { aggregate(bookId.value, it) }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> =
        downloadDao.observeAll().map { downloads ->
            downloads
                .groupBy { it.bookId }
                .mapValues { (bookId, files) -> aggregate(bookId, files) }
        }

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> =
        downloadDao.observeAll().map { downloads ->
            val completedByBook =
                downloads
                    .filter { it.state == DownloadState.COMPLETED }
                    .groupBy { it.bookId }

            if (completedByBook.isEmpty()) return@map emptyList()

            val books =
                bookRepository
                    .getBookListItems(completedByBook.keys.toList())
                    .associateBy { it.id.value }

            completedByBook
                .mapNotNull { (bookId, files) ->
                    val book = books[bookId] ?: return@mapNotNull null
                    DownloadedBookSummary(
                        bookId = bookId,
                        title = book.title,
                        authorNames = book.authorNames,
                        coverBlurHash = book.coverBlurHash,
                        sizeBytes = files.sumOf { it.downloadedBytes },
                        fileCount = files.size,
                    )
                }.sortedByDescending { it.sizeBytes }
        }

    override suspend fun getLocalPath(audioFileId: String): String? = downloadDao.getLocalPath(audioFileId)

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadStatus? =
        downloadDao.getByAudioFileId(audioFileId)?.state?.toDomain()

    // --- State-transition writes ---

    override suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.DOWNLOADING, startedAt)
        }

    override suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateProgress(audioFileId, downloadedBytes, totalBytes)
        }

    override suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markCompleted(audioFileId, localPath, completedAt)
        }

    // Conditional pause (B7): a late NonCancellable cleanup from a dying worker must NOT clobber a
    // terminal state (CANCELLED/DELETED/COMPLETED) back to PAUSED. The guarded DAO query makes the
    // terminal write win at the DB layer regardless of the cancel-vs-cleanup scheduling.
    override suspend fun markPaused(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markPausedIfNotTerminal(audioFileId)
        }

    override suspend fun markCancelled(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.CANCELLED)
        }

    override suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateError(audioFileId, error.message)
        }

    // --- Orchestration (Phase B: stub; Phase C/D move platform code onto these) ---

    @Suppress("NotImplementedDeclaration") // Phase C/D scope — intentional stub per W8 design
    override suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome> =
        throw NotImplementedError(
            "DownloadRepository.enqueueForBook is Phase C/D scope. Phase B keeps " +
                "orchestration on DownloadManager (Android) and AppleDownloadService (iOS).",
        )

    override suspend fun cancelForBook(bookId: BookId): AppResult<Unit> =
        suspendRunCatching {
            val rows = downloadDao.getForBook(bookId.value)
            // Transition all non-terminal rows to CANCELLED.
            for (row in rows) {
                if (row.state != DownloadState.COMPLETED && row.state != DownloadState.DELETED) {
                    // Best-effort per row — a single failed transition shouldn't abort the cancel sweep.
                    val _ = markCancelled(row.audioFileId)
                }
            }
        }

    override suspend fun deleteForBook(bookId: String) {
        downloadDao.deleteForBook(bookId)
    }

    override suspend fun deleteDeletedRecordsForBook(bookId: String) {
        downloadDao.deleteDeletedRecordsForBook(bookId)
    }

    override suspend fun resumeIncompleteDownloads(): AppResult<Unit> =
        suspendRunCatching {
            // Re-enqueue incomplete downloads via the platform enqueuer.
            // Note: existing DownloadManager.resumeIncompleteDownloads also runs at app startup and is
            // the primary recovery path; this method exists for parity. Phase E may consolidate.
            val incomplete = downloadDao.getIncomplete()
            for (row in incomplete) {
                enqueuer.enqueue(row)
            }
        }

    // --- Aggregation reducer (shared across platforms) ---

    private fun aggregate(
        bookId: String,
        downloads: List<DownloadEntity>,
    ): BookDownloadStatus = aggregateBookDownloadStatus(bookId, downloads)
}

/**
 * Reduce a book's per-file [DownloadEntity] rows to an aggregated [BookDownloadStatus].
 *
 * Shared across [DownloadRepositoryImpl] and the iOS `AppleDownloadService` so both platforms apply
 * the identical state machine. `internal` — a reducer, not part of the exported surface.
 *
 * **Completeness is measured against every non-tombstone row, not the surviving subset.** A book is
 * [BookDownloadStatus.Completed] — the only status that reports `isFullyDownloaded` — only when
 * *all* of its non-DELETED rows are COMPLETED. This closes the "cancelled partial reports Downloaded"
 * bug: a book with some COMPLETED files and some CANCELLED files is a stopped partial, surfaced as
 * [BookDownloadStatus.Paused] (whose contract covers "user cancelled or system paused") so the UI
 * offers resume and availability withholds `canPlay`/emits the offline server warning — never a false
 * "Downloaded". A book whose rows are *all* cancelled (nothing on disk) collapses to
 * [BookDownloadStatus.NotDownloaded] so the user can cleanly re-download.
 */
internal fun aggregateBookDownloadStatus(
    bookId: String,
    downloads: List<DownloadEntity>,
): BookDownloadStatus {
    // DELETED rows are tombstones ("explicitly deleted"); they never count toward completeness.
    val relevant = downloads.filter { it.state != DownloadState.DELETED }
    if (relevant.isEmpty()) {
        return BookDownloadStatus.NotDownloaded(bookId)
    }
    // Fully cancelled with nothing downloaded → treat as not-downloaded so the user can restart clean.
    if (relevant.all { it.state == DownloadState.CANCELLED }) {
        return BookDownloadStatus.NotDownloaded(bookId)
    }
    val totalFiles = relevant.size
    val completedFiles = relevant.count { it.state == DownloadState.COMPLETED }
    val totalBytes = relevant.sumOf { it.totalBytes }
    val downloadedBytes = relevant.sumOf { it.downloadedBytes }
    val hasActive = relevant.any { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
    return when {
        relevant.all { it.state == DownloadState.COMPLETED } -> {
            BookDownloadStatus.Completed(bookId = bookId, totalBytes = totalBytes)
        }

        relevant.any { it.state == DownloadState.FAILED } -> {
            BookDownloadStatus.Failed(
                bookId = bookId,
                errorMessage =
                    relevant
                        .firstOrNull { it.state == DownloadState.FAILED }
                        ?.errorMessage
                        ?: "Download failed",
                partiallyDownloadedFiles = completedFiles,
            )
        }

        hasActive -> {
            BookDownloadStatus.InProgress(
                bookId = bookId,
                totalFiles = totalFiles,
                downloadingFiles = relevant.count { it.state == DownloadState.DOWNLOADING },
                completedFiles = completedFiles,
                totalBytes = totalBytes,
                downloadedBytes = downloadedBytes,
            )
        }

        // Nothing active, not all complete, no failures: a stopped partial (a mix of
        // PAUSED / CANCELLED / COMPLETED). Surface as Paused so the UI offers resume.
        else -> {
            BookDownloadStatus.Paused(
                bookId = bookId,
                pausedFiles =
                    relevant.count {
                        it.state == DownloadState.PAUSED || it.state == DownloadState.CANCELLED
                    },
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )
        }
    }
}

// --- Entity → domain mapping (internal so commonTest can use the canonical definitions) ---

internal fun DownloadEntity.toDomain(): Download =
    Download(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = filename,
        fileIndex = fileIndex,
        status = state.toDomain(),
        localPath = localPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        queuedAt = queuedAt,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage,
        retryCount = retryCount,
    )

internal fun DownloadState.toDomain(): DownloadStatus =
    when (this) {
        DownloadState.QUEUED -> DownloadStatus.QUEUED
        DownloadState.DOWNLOADING -> DownloadStatus.DOWNLOADING
        DownloadState.PAUSED -> DownloadStatus.PAUSED
        DownloadState.COMPLETED -> DownloadStatus.COMPLETED
        DownloadState.FAILED -> DownloadStatus.FAILED
        DownloadState.DELETED -> DownloadStatus.DELETED
        DownloadState.CANCELLED -> DownloadStatus.CANCELLED
    }
