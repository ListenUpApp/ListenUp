package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.playback.PlaybackBandwidthCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default [AudioFileDownloader] backed by the authenticated [ApiClientFactory] client.
 *
 * Lives in `:app:sharedLogic` so it can call [ApiClientFactory.getClient] internally — keeping the Ktor
 * `HttpClient` off every cross-module surface. Delegates the actual streaming to [downloadAudioFile],
 * which carries the resume/progress/cancellation logic.
 *
 * Also drives the "playback preempts downloads" yield: between chunks it consults
 * [playbackBandwidthCoordinator] and soft-pauses while a stream is buffering — bounded by [maxYield]
 * so a sustained stall can't starve the download indefinitely (or trip WorkManager's run limit).
 */
internal class AudioFileDownloaderImpl(
    private val apiClientFactory: ApiClientFactory,
    private val repository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val prepareRepository: PlaybackPrepareRepository,
    private val playbackBandwidthCoordinator: PlaybackBandwidthCoordinator,
    private val maxYield: Duration = 30.seconds,
) : AudioFileDownloader {
    override suspend fun download(
        audioFileId: String,
        bookId: String,
        filename: String,
        expectedSize: Long,
        isStopped: () -> Boolean,
        setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): AppResult<Unit> =
        downloadAudioFile(
            audioFileId = audioFileId,
            bookId = bookId,
            filename = filename,
            expectedSize = expectedSize,
            httpClient = apiClientFactory.getClient(),
            repository = repository,
            fileManager = fileManager,
            prepareRepository = prepareRepository,
            isStopped = isStopped,
            setProgress = setProgress,
            yieldToPlayback = ::yieldToPlayback,
        )

    /**
     * Suspend while playback is buffering a stream, up to [maxYield]. Returns immediately when not
     * yielding (the common case). The cap guarantees the download eventually makes progress even
     * during a long stall, so background downloads are throttled — never permanently starved.
     */
    private suspend fun yieldToPlayback() {
        if (playbackBandwidthCoordinator.shouldYield.value) {
            withTimeoutOrNull(maxYield) {
                playbackBandwidthCoordinator.shouldYield.first { !it }
            }
        }
    }
}
