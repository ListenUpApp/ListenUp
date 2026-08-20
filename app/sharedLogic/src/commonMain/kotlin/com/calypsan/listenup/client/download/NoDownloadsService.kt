package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The [DownloadService] for platforms with no download backend at all — currently Desktop and
 * the browser. One class, not one per platform: both stubs were verbatim duplicates before this,
 * and [DownloadService] growing a new member (like [supportsDownloads]) is exactly the kind of
 * change that would have silently drifted between two copies.
 *
 * Returns typed failures instead of throwing, the same call `BrowserDownloadEnqueuer` makes and
 * for the same reason: [PlaybackManagerImpl][com.calypsan.listenup.client.playback.PlaybackManagerImpl]
 * and `DownloadRepository` walk this seam during ordinary playback and library browsing, not just
 * on an explicit download tap — a throw here would strand a screen that never asked to download
 * anything. [DownloadError.NotSupported] (not [DownloadError.DownloadFailed]) is deliberate: this
 * is not a transient failure worth retrying, it is a platform that will never be able to download,
 * no matter how many times it's asked — see [supportsDownloads].
 */
class NoDownloadsService : DownloadService {
    override val supportsDownloads: Boolean = false

    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = false

    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Failure(DownloadError.NotSupported())

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
        // No-op: downloads not supported
    }
}
