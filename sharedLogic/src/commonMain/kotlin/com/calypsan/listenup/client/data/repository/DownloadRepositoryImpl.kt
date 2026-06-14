package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
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
class DownloadRepositoryImpl(
    private val downloadDao: DownloadDao,
    private val bookRepository: BookRepository,
    private val enqueuer: DownloadEnqueuer,
) : DownloadRepository {
    // --- Reads ---

    override fun observeForBook(bookId: BookId): Flow<List<DownloadEntity>> = downloadDao.observeForBook(bookId.value)

    override fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

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

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadState? =
        downloadDao.getByAudioFileId(audioFileId)?.state

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

    override suspend fun markPaused(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.PAUSED)
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
                    markCancelled(row.audioFileId)
                }
            }
        }

    override suspend fun deleteForBook(bookId: String) {
        downloadDao.deleteForBook(bookId)
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
    ): BookDownloadStatus {
        if (downloads.isEmpty()) {
            return BookDownloadStatus.NotDownloaded(bookId)
        }
        val activeDownloads =
            downloads.filter {
                it.state != DownloadState.DELETED && it.state != DownloadState.CANCELLED
            }
        if (activeDownloads.isEmpty()) {
            return BookDownloadStatus.NotDownloaded(bookId)
        }
        val totalFiles = activeDownloads.size
        val completedFiles = activeDownloads.count { it.state == DownloadState.COMPLETED }
        val totalBytes = activeDownloads.sumOf { it.totalBytes }
        val downloadedBytes = activeDownloads.sumOf { it.downloadedBytes }
        return when {
            activeDownloads.all { it.state == DownloadState.COMPLETED } -> {
                BookDownloadStatus.Completed(bookId = bookId, totalBytes = totalBytes)
            }

            activeDownloads.any { it.state == DownloadState.FAILED } -> {
                BookDownloadStatus.Failed(
                    bookId = bookId,
                    errorMessage =
                        activeDownloads
                            .firstOrNull { it.state == DownloadState.FAILED }
                            ?.errorMessage
                            ?: "Download failed",
                    partiallyDownloadedFiles = completedFiles,
                )
            }

            activeDownloads.all { it.state == DownloadState.PAUSED } -> {
                BookDownloadStatus.Paused(
                    bookId = bookId,
                    pausedFiles = activeDownloads.size,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            }

            else -> {
                BookDownloadStatus.InProgress(
                    bookId = bookId,
                    totalFiles = totalFiles,
                    downloadingFiles = activeDownloads.count { it.state == DownloadState.DOWNLOADING },
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                )
            }
        }
    }
}
