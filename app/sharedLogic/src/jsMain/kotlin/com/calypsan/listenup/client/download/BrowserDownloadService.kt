package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Stub [DownloadService] for the browser — the same shape as Desktop's `StubDownloadService`
 * (`:app:sharedUI` desktopMain), reimplemented here rather than shared because that class sits
 * in a module the browser does not depend on.
 *
 * Offline audio in a browser is undesigned (see [DownloadFileManager], which throws on purpose),
 * so this exists only so [com.calypsan.listenup.client.playback.PlaybackManagerImpl] — which
 * takes a [DownloadService] unconditionally on every platform — has something to construct
 * against. Streaming-only playback is the whole story on web today.
 */
internal class BrowserDownloadService : DownloadService {
    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = false

    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Downloads are not supported in the browser"))

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
        // No-op: downloads not supported on the browser
    }
}
