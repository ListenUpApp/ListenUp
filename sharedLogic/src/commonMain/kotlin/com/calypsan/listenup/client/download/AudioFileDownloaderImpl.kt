package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository

/**
 * Default [AudioFileDownloader] backed by the authenticated [ApiClientFactory] client.
 *
 * Lives in `:sharedLogic` so it can call [ApiClientFactory.getClient] internally — keeping the
 * Ktor `HttpClient` off every cross-module surface. Delegates the actual streaming to
 * [downloadAudioFile], which carries the resume/progress/cancellation logic.
 */
internal class AudioFileDownloaderImpl(
    private val apiClientFactory: ApiClientFactory,
    private val repository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val playbackRpcFactory: PlaybackRpcFactory,
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
            playbackRpcFactory = playbackRpcFactory,
            isStopped = isStopped,
            setProgress = setProgress,
        )
}
