package com.calypsan.listenup.client.platform

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Stub implementation of [DownloadService] for desktop.
 *
 * Desktop downloads are not yet implemented. This stub allows the app
 * to compile and run with streaming-only playback.
 *
 * TODO: Implement background downloads using kotlinx-io and coroutines.
 */
class StubDownloadService : DownloadService {
    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = false

    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Downloads not yet supported on desktop"))

    override suspend fun cancelDownload(bookId: BookId) {
        // No-op: downloads not supported
    }

    override suspend fun deleteDownload(bookId: BookId) {
        // No-op: downloads not supported
    }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        flowOf(BookDownloadStatus.NotDownloaded(bookId.value))

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> = flowOf(emptyMap())

    override suspend fun resumeIncompleteDownloads() {
        // No-op: downloads not supported on desktop
    }
}
