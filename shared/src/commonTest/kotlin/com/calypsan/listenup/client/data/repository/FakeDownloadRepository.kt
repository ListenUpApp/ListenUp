package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
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
 * Per project memory `feedback_fakes_for_seams.md` — validated test pattern is hand-rolled fakes
 * implementing the seam interface. State lives in a [MutableStateFlow] so observers see updates.
 *
 * Optional [enqueueFailure] lambda lets tests inject failure for [enqueueForBook] (returns the
 * lambda's value when set; `AppResult.Success(DownloadOutcome.Started)` when null).
 *
 * Phase B carveout: orchestration methods (`cancelForBook`, `resumeForAudioFile`,
 * `resumeIncompleteDownloads`) are intentional no-ops in this fake — they're filled in
 * meaningfully when Phase C/D move platform code onto the repository.
 */
open class FakeDownloadRepository(
    initial: List<DownloadEntity> = emptyList(),
    private val enqueueFailure: ((BookId) -> AppResult<DownloadOutcome>)? = null,
) : DownloadRepository {
    private val state = MutableStateFlow(initial.associateBy { it.audioFileId })

    /** All entities currently in the fake (test-only inspection). */
    val entities: List<DownloadEntity> get() = state.value.values.toList()

    // --- Reads ---

    override fun observeForBook(bookId: BookId): Flow<List<DownloadEntity>> =
        state.asStateFlow().map { it.values.filter { e -> e.bookId == bookId.value } }

    override fun observeAll(): Flow<List<DownloadEntity>> = state.asStateFlow().map { it.values.toList() }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        observeForBook(bookId).map { aggregate(bookId.value, it) }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> =
        observeAll().map { downloads ->
            downloads
                .groupBy { it.bookId }
                .mapValues { (bid, files) -> aggregate(bid, files) }
        }

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> = observeAll().map { _ -> emptyList() }

    override suspend fun getLocalPath(audioFileId: String): String? =
        state.value[audioFileId]?.takeIf { it.state == DownloadState.COMPLETED }?.localPath

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadState? = state.value[audioFileId]?.state

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

    override suspend fun markWaitingForServer(
        audioFileId: String,
        transcodeJobId: String,
    ): AppResult<Unit> {
        update(audioFileId) {
            it.copy(state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = transcodeJobId)
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

    private val _resumedAudioFiles = mutableListOf<String>()

    /** Test helper: list of audioFileIds that resumeForAudioFile was called with (excludes silently-dropped late events). */
    val resumedAudioFiles: List<String> get() = _resumedAudioFiles.toList()

    override suspend fun resumeForAudioFile(audioFileId: String): AppResult<Unit> {
        val entity =
            state.value[audioFileId]
                ?: return AppResult.Failure(
                    DownloadError.DownloadFailed(
                        debugInfo = "No download row for $audioFileId",
                    ),
                )
        if (entity.state == DownloadState.CANCELLED || entity.state == DownloadState.COMPLETED) {
            return AppResult.Success(Unit) // late event tolerance
        }
        _resumedAudioFiles.add(audioFileId)
        return AppResult.Success(Unit)
    }

    override suspend fun resumeIncompleteDownloads(): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Phase D production impl re-issues `preparePlayback` for WAITING_FOR_SERVER rows. The fake
     * leaves this as a no-op deliberately — mirroring would require injecting a fake
     * `PlaybackApiContract` into the fake's constructor, cascading to every test that uses it.
     * Tests that need recheck behavior should use `DownloadRepositoryImplTest` against the real
     * impl with a `FakePlaybackApiContract`.
     */
    override suspend fun recheckWaitingForServer(): AppResult<Unit> = AppResult.Success(Unit)

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

    private fun aggregate(
        bookId: String,
        downloads: List<DownloadEntity>,
    ): BookDownloadStatus {
        if (downloads.isEmpty()) return BookDownloadStatus.NotDownloaded(bookId)
        val activeDownloads =
            downloads.filter {
                it.state != DownloadState.DELETED && it.state != DownloadState.CANCELLED
            }
        if (activeDownloads.isEmpty()) return BookDownloadStatus.NotDownloaded(bookId)
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
                        activeDownloads.firstOrNull { it.state == DownloadState.FAILED }?.errorMessage
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
                    waitingForServerFiles = activeDownloads.count { it.state == DownloadState.WAITING_FOR_SERVER },
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                )
            }
        }
    }
}
