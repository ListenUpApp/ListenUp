package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Derives [BookAvailability.State] from download status, server reachability, network
 * metering, and the wifi-only preference. Pulled out of BookDetailViewModel so the
 * availability logic is independently testable and the VM stays lean.
 */
internal class DefaultBookAvailability(
    private val downloadRepository: DownloadRepository,
    private val serverReachability: ServerReachability,
    private val networkMonitor: NetworkMonitor,
    private val localPreferences: LocalPreferences,
    private val playbackAvailable: Boolean,
) : BookAvailability {
    override fun observe(bookId: BookId): Flow<BookAvailability.State> =
        combine(
            downloadRepository.observeBookStatus(bookId),
            serverReachability.state,
            networkMonitor.isOnUnmeteredNetworkFlow,
            localPreferences.wifiOnlyDownloads,
        ) { downloadStatus, reachability, unmetered, wifiOnly ->
            val isFullyDownloaded = downloadStatus is BookDownloadStatus.Completed
            val isQueued =
                downloadStatus is BookDownloadStatus.InProgress &&
                    downloadStatus.downloadingFiles == 0 &&
                    downloadStatus.completedFiles == 0
            BookAvailability.State(
                downloadStatus = downloadStatus,
                isPlaybackAvailable = playbackAvailable,
                canPlay = isFullyDownloaded || reachability != Reachability.Unreachable,
                canDownload = playbackAvailable && reachability == Reachability.Reachable,
                showServerWarning = reachability == Reachability.Unreachable && !isFullyDownloaded,
                isWaitingForWifi = isQueued && wifiOnly && !unmetered,
            )
        }
}
