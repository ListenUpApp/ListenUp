package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.download.DownloadManager
import com.calypsan.listenup.client.playback.PlaybackManager
import android.content.Context
import android.content.Intent
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Android implementation of BookDetailPlatformActions.
 * Delegates to DownloadManager (WorkManager) and NowPlayingViewModel (Media3).
 */
class AndroidBookDetailPlatformActions(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val nowPlayingViewModel: NowPlayingViewModel,
    private val localPreferences: LocalPreferences,
    private val networkMonitor: NetworkMonitor,
    private val playbackManager: PlaybackManager,
) : BookDetailPlatformActions {
    override val isPlaybackAvailable: Boolean = true

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> = downloadManager.observeBookStatus(bookId)

    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> = downloadManager.downloadBook(bookId)

    override suspend fun cancelDownload(bookId: BookId) = downloadManager.cancelDownload(bookId)

    override suspend fun deleteDownload(bookId: BookId) = downloadManager.deleteDownload(bookId)

    override fun playBook(bookId: BookId) = nowPlayingViewModel.playBook(bookId)

    override fun observeWifiOnlyDownloads(): Flow<Boolean> = localPreferences.wifiOnlyDownloads

    override fun observeIsOnUnmeteredNetwork(): Flow<Boolean> = networkMonitor.isOnUnmeteredNetworkFlow

    override suspend fun checkServerReachable(): Boolean = playbackManager.isServerReachable()

    override fun shareText(
        text: String,
        url: String,
    ) {
        val sendIntent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
        val shareIntent =
            Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(shareIntent)
    }
}
