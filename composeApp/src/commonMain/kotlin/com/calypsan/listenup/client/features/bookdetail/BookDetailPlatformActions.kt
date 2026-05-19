package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Platform-specific actions for the Book Detail screen.
 *
 * Android: Provides full download management and playback via WorkManager + Media3
 * Desktop: No-op implementation (downloads and playback not yet available)
 */
interface BookDetailPlatformActions {
    /** Whether download/playback features are available on this platform */
    val isPlaybackAvailable: Boolean

    /** Observe download status for a book */
    fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus>

    /** Start downloading a book */
    suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome>

    /** Cancel an in-progress download */
    suspend fun cancelDownload(bookId: BookId)

    /** Delete downloaded files for a book */
    suspend fun deleteDownload(bookId: BookId)

    /** Start playback for a book */
    fun playBook(bookId: BookId)

    /** Observe WiFi-only downloads preference */
    fun observeWifiOnlyDownloads(): Flow<Boolean>

    /** Observe whether device is on unmetered network */
    fun observeIsOnUnmeteredNetwork(): Flow<Boolean>

    /** Check if the server is reachable (quick health check) */
    suspend fun checkServerReachable(): Boolean

    /** Share text via platform share sheet (Android) or clipboard (Desktop) */
    fun shareText(
        text: String,
        url: String,
    )
}

/**
 * No-op implementation for platforms without download/playback support.
 */
class NoOpBookDetailPlatformActions : BookDetailPlatformActions {
    override val isPlaybackAvailable: Boolean = false

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        flowOf(BookDownloadStatus.NotDownloaded(bookId.value))

    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Not available on this platform"))

    override suspend fun cancelDownload(bookId: BookId) {}

    override suspend fun deleteDownload(bookId: BookId) {}

    override fun playBook(bookId: BookId) {}

    override fun observeWifiOnlyDownloads(): Flow<Boolean> = flowOf(false)

    override fun observeIsOnUnmeteredNetwork(): Flow<Boolean> = flowOf(true)

    override suspend fun checkServerReachable(): Boolean = true

    override fun shareText(
        text: String,
        url: String,
    ) {}
}
