package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Download
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [DownloadRepository] fake for seam tests. Only the read side used by the now-playing
 * teardown ([observeBookStatus]) is stateful and controllable; the write/orchestration side returns
 * benign defaults. Seed per-book statuses via [setStatus] and observers re-emit.
 */
class FakeDownloadRepository(
    initialStatuses: Map<String, BookDownloadStatus> = emptyMap(),
) : DownloadRepository {
    private val statuses = MutableStateFlow(initialStatuses)

    /** Test helper: set a book's aggregated download status, emitting to observers. */
    fun setStatus(
        bookId: String,
        status: BookDownloadStatus,
    ) {
        statuses.value = statuses.value + (bookId to status)
    }

    override fun observeForBook(bookId: BookId): Flow<List<Download>> = flowOf(emptyList())

    override fun observeAll(): Flow<List<Download>> = flowOf(emptyList())

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        statuses.map { it[bookId.value] ?: BookDownloadStatus.NotDownloaded(bookId.value) }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> = statuses

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> = flowOf(emptyList())

    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadStatus? = null

    override suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markPaused(audioFileId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markCancelled(audioFileId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Success(DownloadOutcome.Started)

    override suspend fun cancelForBook(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteForBook(bookId: String) = Unit

    override suspend fun resumeIncompleteDownloads(): AppResult<Unit> = AppResult.Success(Unit)
}
