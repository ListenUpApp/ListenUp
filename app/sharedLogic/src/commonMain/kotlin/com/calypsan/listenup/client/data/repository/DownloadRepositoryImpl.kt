package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.core.TypedAppErrorException
import com.calypsan.listenup.client.core.pullCatching
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_RETRIES
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Download
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.model.InProgressBookSummary
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.domain.model.BookListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** SQLite binds one host parameter per `IN (:list)` element; chunk below the ~999 ceiling. */
private const val SQLITE_IN_CLAUSE_CHUNK_SIZE = 900

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

    /**
     * The downloads table is written every 256 KB or 500 ms per file while anything downloads, and
     * the Storage screen walks the whole downloads tree per emission — so the table is reduced to
     * its finished files first and only a change there passes. The books are observed, not read
     * once, so a title or cover that syncs in later shows without waiting for the next download.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> =
        downloadDao
            .observeAll()
            .map(::completedFilesByBook)
            .distinctUntilChanged()
            .flatMapLatest { completedByBook ->
                if (completedByBook.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    bookRepository.observeBookListItems(completedByBook.keys.toList()).map { books ->
                        summarize(completedByBook, books)
                    }
                }
            }.distinctUntilChanged()

    /** Finished files per book: what [observeDownloadedBooks] shows, and nothing a progress write moves. */
    private fun completedFilesByBook(downloads: List<DownloadEntity>): Map<String, CompletedFiles> =
        downloads
            .filter { it.state == DownloadState.COMPLETED }
            .groupBy { it.bookId }
            .mapValues { (_, files) -> CompletedFiles(count = files.size, bytes = files.sumOf { it.downloadedBytes }) }

    private fun summarize(
        completedByBook: Map<String, CompletedFiles>,
        books: List<BookListItem>,
    ): List<DownloadedBookSummary> {
        val booksById = books.associateBy { it.id.value }
        return completedByBook
            .mapNotNull { (bookId, files) ->
                val book = booksById[bookId] ?: return@mapNotNull null
                DownloadedBookSummary(
                    bookId = bookId,
                    title = book.title,
                    authorNames = book.authorNames,
                    sizeBytes = files.bytes,
                    fileCount = files.count,
                    coverPath = book.coverPath,
                    coverHash = book.coverHash,
                )
            }.sortedByDescending { it.sizeBytes }
    }

    /** A book's finished downloads, reduced to what the Storage list shows. */
    private data class CompletedFiles(
        val count: Int,
        val bytes: Long,
    )

    override fun observeInProgressBooks(): Flow<List<InProgressBookSummary>> =
        observeAllStatuses()
            .map { statuses ->
                statuses.filterValues { status ->
                    status is BookDownloadStatus.InProgress || status is BookDownloadStatus.Paused ||
                        status is BookDownloadStatus.Failed
                }
            }.distinctUntilChanged()
            .map { unfinished ->
                if (unfinished.isEmpty()) return@map emptyList()
                val books = bookRepository.getBookListItems(unfinished.keys.toList()).associateBy { it.id.value }
                unfinished.mapNotNull { (bookId, status) ->
                    books[bookId]?.let { book ->
                        InProgressBookSummary(
                            bookId = bookId,
                            title = book.title,
                            authorNames = book.authorNames,
                            coverPath = book.coverPath,
                            coverHash = book.coverHash,
                            status = status,
                        )
                    }
                }
            }

    override suspend fun getLocalPath(audioFileId: String): String? = downloadDao.getLocalPath(audioFileId)

    override suspend fun getLocalPaths(audioFileIds: List<String>): Map<String, String> {
        if (audioFileIds.isEmpty()) return emptyMap()
        // Split so the suspending DAO call stays in an inline List op (a Sequence lambda cannot suspend)
        // while no single chain stacks more than two intermediate collections.
        val distinctIds = audioFileIds.distinct()
        val rows = distinctIds.chunked(SQLITE_IN_CLAUSE_CHUNK_SIZE).flatMap { downloadDao.getLocalPaths(it) }
        return rows
            .mapNotNull { row -> row.localPath?.let { row.audioFileId to it } }
            .toMap()
    }

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadStatus? =
        downloadDao.getByAudioFileId(audioFileId)?.run { state.toDomain() }

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
    ): AppResult<Boolean> =
        suspendRunCatching {
            downloadDao.markCompleted(audioFileId, localPath, completedAt) > 0
        }

    // Conditional pause (B7): a late NonCancellable cleanup from a dying worker must NOT clobber a
    // terminal state (CANCELLED/DELETED/COMPLETED) back to PAUSED. The guarded DAO query makes the
    // terminal write win at the DB layer regardless of the cancel-vs-cleanup scheduling.
    override suspend fun markPaused(
        audioFileId: String,
        reason: DownloadPauseReason,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markPausedIfNotTerminal(audioFileId, reason)
        }

    override suspend fun markQueued(
        audioFileId: String,
        reason: DownloadPauseReason?,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markQueuedIfNotTerminal(audioFileId, reason)
        }

    override suspend fun markTransferring(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markTransferringIfLive(audioFileId, startedAt)
        }

    override suspend fun markCancelled(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.CANCELLED)
        }

    override suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Int> =
        suspendRunCatching {
            downloadDao.markFailedIfNotTerminal(audioFileId, error.message)
            // A row deleted underneath the write has nothing left to retry: report the budget spent.
            downloadDao.getByAudioFileId(audioFileId)?.retryCount ?: MAX_DOWNLOAD_RETRIES
        }

    // --- Orchestration (Phase B: stub; Phase C/D move platform code onto these) ---

    @Suppress("NotImplementedDeclaration") // Phase C/D scope — intentional stub per W8 design
    override suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome> =
        throw NotImplementedError(
            "DownloadRepository.enqueueForBook is Phase C/D scope. Phase B keeps " +
                "orchestration on DownloadManager (Android) and AppleDownloadService (iOS).",
        )

    override suspend fun cancelForBook(bookId: BookId): AppResult<Unit> =
        pullCatching {
            val rows = downloadDao.getForBook(bookId.value)
            // Transition all non-terminal rows to CANCELLED. A failed transition is NOT
            // swallowed (honest-over-silent, audit finding): reporting Success while a row is
            // still DOWNLOADING leaves aggregateBookDownloadStatus stuck on InProgress forever —
            // a permanent spinner with no worker behind it and no way to retry or dismiss.
            for (row in rows) {
                if (row.state != DownloadState.COMPLETED && row.state != DownloadState.DELETED) {
                    markCancelled(row.audioFileId).getOrElse { error -> throw TypedAppErrorException(error) }
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
 * offers resume and availability emits the offline server warning hint — never a false
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
            val downloadingFiles = relevant.count { it.state == DownloadState.DOWNLOADING }
            BookDownloadStatus.InProgress(
                bookId = bookId,
                totalFiles = totalFiles,
                downloadingFiles = downloadingFiles,
                completedFiles = completedFiles,
                totalBytes = totalBytes,
                downloadedBytes = downloadedBytes,
                resumingAfterClose =
                    downloadingFiles == 0 &&
                        relevant.any { row ->
                            row.state == DownloadState.QUEUED &&
                                row.pauseReason == DownloadPauseReason.APP_CLOSED
                        },
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
                completedFiles = completedFiles,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                reason = pausedReason(relevant),
            )
        }
    }
}

/**
 * Why a stopped book is stopped: the person's own pause wins over any wait the system imposed on
 * another of its files, because only the person can undo it. Null for a cancelled partial.
 */
private fun pausedReason(rows: List<DownloadEntity>): DownloadPauseReason? {
    val reasons = rows.filter { it.state == DownloadState.PAUSED }.mapNotNull { it.pauseReason }
    return if (DownloadPauseReason.PAUSED_BY_YOU in
        reasons
    ) {
        DownloadPauseReason.PAUSED_BY_YOU
    } else {
        reasons.firstOrNull()
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
