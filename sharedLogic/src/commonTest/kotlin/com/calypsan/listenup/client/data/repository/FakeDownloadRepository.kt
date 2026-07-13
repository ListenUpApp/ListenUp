package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.toDomain
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Download
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory fake implementing [DownloadRepository] for seam-level tests.
 *
 * The validated test pattern is hand-rolled fakes implementing the seam interface.
 * State lives in a [MutableStateFlow] so observers see updates.
 *
 * Optional [enqueueFailure] lambda lets tests inject failure for [enqueueForBook] (returns the
 * lambda's value when set; `AppResult.Success(DownloadOutcome.Started)` when null).
 */
internal open class FakeDownloadRepository(
    initial: List<DownloadEntity> = emptyList(),
    private val enqueueFailure: ((BookId) -> AppResult<DownloadOutcome>)? = null,
) : DownloadRepository {
    private val state = MutableStateFlow(initial.associateBy { it.audioFileId })

    /** All entities currently in the fake (test-only inspection). */
    val entities: List<DownloadEntity> get() = state.value.values.toList()

    // --- Reads ---

    override fun observeForBook(bookId: BookId): Flow<List<Download>> =
        state.asStateFlow().map { it.values.filter { e -> e.bookId == bookId.value }.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Download>> =
        state.asStateFlow().map { it.values.toList().map { it.toDomain() } }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        state.asStateFlow().map { entities ->
            aggregate(bookId.value, entities.values.filter { it.bookId == bookId.value })
        }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> =
        state.asStateFlow().map { entities ->
            entities.values
                .groupBy { it.bookId }
                .mapValues { (bid, files) -> aggregate(bid, files) }
        }

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> = observeAll().map { _ -> emptyList() }

    override suspend fun getLocalPath(audioFileId: String): String? =
        state.value[audioFileId]?.takeIf { it.state == DownloadState.COMPLETED }?.localPath

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadStatus? =
        state.value[audioFileId]?.state?.toDomain()

    // --- State-transition writes ---

    override suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> {
        update(audioFileId) { it.copy(state = DownloadState.DOWNLOADING, startedAt = startedAt) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): AppResult<Unit> {
        update(audioFileId) { it.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes) }
        return AppResult.Success(Unit)
    }

    override suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): AppResult<Unit> {
        update(audioFileId) {
            it.copy(
                state = DownloadState.COMPLETED,
                localPath = localPath,
                completedAt = completedAt,
                downloadedBytes = it.totalBytes,
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun markPaused(audioFileId: String): AppResult<Unit> {
        update(audioFileId) { it.copy(state = DownloadState.PAUSED) }
        return AppResult.Success(Unit)
    }

    override suspend fun markCancelled(audioFileId: String): AppResult<Unit> {
        update(audioFileId) { it.copy(state = DownloadState.CANCELLED) }
        return AppResult.Success(Unit)
    }

    override suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Unit> {
        update(audioFileId) {
            it.copy(state = DownloadState.FAILED, errorMessage = error.message)
        }
        return AppResult.Success(Unit)
    }

    // --- Orchestration ---

    override suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome> =
        enqueueFailure?.invoke(bookId) ?: AppResult.Success(DownloadOutcome.Started)

    override suspend fun cancelForBook(bookId: BookId): AppResult<Unit> {
        val rowsForBook = state.value.values.filter { it.bookId == bookId.value }
        for (row in rowsForBook) {
            if (row.state != DownloadState.COMPLETED && row.state != DownloadState.DELETED) {
                update(row.audioFileId) { it.copy(state = DownloadState.CANCELLED) }
            }
        }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteForBook(bookId: String) {
        state.update { current -> current.filterValues { it.bookId != bookId } }
    }

    override suspend fun deleteDeletedRecordsForBook(bookId: String) {
        state.update { current ->
            current.filterValues { it.bookId != bookId || it.state != DownloadState.DELETED }
        }
    }

    override suspend fun resumeIncompleteDownloads(): AppResult<Unit> = AppResult.Success(Unit)

    // --- Test helpers ---

    /** Seed an entity directly (test setup convenience). */
    fun seed(entity: DownloadEntity) {
        state.update { current -> current + (entity.audioFileId to entity) }
    }

    private fun update(
        audioFileId: String,
        transform: (DownloadEntity) -> DownloadEntity,
    ) {
        state.update { current ->
            val existing = current[audioFileId] ?: return@update current
            current + (audioFileId to transform(existing))
        }
    }

    // Delegates to the production reducer so the fake and DownloadRepositoryImpl stay in lock-step.
    private fun aggregate(
        bookId: String,
        downloads: List<DownloadEntity>,
    ): BookDownloadStatus = aggregateBookDownloadStatus(bookId, downloads)
}
