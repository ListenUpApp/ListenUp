@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * Result of [resolveDownloadUrl]. Either a [Ready] URL to download from, or a [WaitForServer]
 * signal that means: write WAITING_FOR_SERVER + exit cleanly; SSE `transcode.complete` will
 * re-enqueue the worker via [DownloadRepository.resumeForAudioFile].
 */
internal sealed interface ResolveResult {
    /** Server is ready to stream the audio at [url]; caller proceeds with the download. */
    data class Ready(
        val url: String,
    ) : ResolveResult

    /**
     * Server is still transcoding under [transcodeJobId]; caller writes WAITING_FOR_SERVER
     * and exits. SSE `transcode.complete` will re-enqueue the worker when the variant is ready.
     */
    data class WaitForServer(
        val transcodeJobId: String,
    ) : ResolveResult
}

private const val PROGRESS_INTERVAL_MS = 500L
private const val BUFFER_SIZE = 8 * 1024
private const val PROGRESS_BYTES_INTERVAL = 256 * 1024L // 256KB — emit progress at least every quarter MB

/**
 * Core download logic for a single audio file, extracted from [DownloadWorker] so it can be
 * driven from commonTest/jvmTest without WorkManager or an Android Context.
 *
 * Returns [AppResult.Success] when the file is fully downloaded (or when the server is still
 * transcoding and the worker should write WAITING_FOR_SERVER and exit). Returns
 * [AppResult.Failure] with a typed [com.calypsan.listenup.api.error.AppError] on any failure;
 * [kotlinx.coroutines.CancellationException] still propagates so callers can distinguish
 * cancellation from failure.
 *
 * Features:
 * - Codec negotiation (downloads transcoded variant if needed)
 * - Resume support (Range headers)
 * - Progress updates via [setProgress] lambda
 * - Cancellation handling via [isStopped] lambda
 *
 * Phase D: transcode-poll loop replaced by WAITING_FOR_SERVER + SSE re-enqueue path.
 */
@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
internal suspend fun downloadAudioFile(
    audioFileId: String,
    bookId: String,
    filename: String,
    expectedSize: Long,
    httpClient: HttpClient,
    repository: DownloadRepository,
    fileManager: DownloadFileManager,
    playbackApi: PlaybackApiContract,
    playbackPreferences: PlaybackPreferences,
    capabilityDetector: AudioCapabilityDetector,
    isStopped: () -> Boolean = { false },
    setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
): AppResult<Unit> =
    suspendRunCatching {
        withContext(Dispatchers.IO) {
            // Resolve the download URL (relative — Ktor's defaultRequest provides the base).
            // Phase D: if transcoding is in progress, write WAITING_FOR_SERVER and exit cleanly.
            // SSE transcode.complete handler will re-enqueue via repository.resumeForAudioFile.
            val resolved =
                resolveDownloadUrl(
                    bookId = bookId,
                    audioFileId = audioFileId,
                    playbackApi = playbackApi,
                    playbackPreferences = playbackPreferences,
                    capabilityDetector = capabilityDetector,
                )
            val url =
                when (resolved) {
                    is ResolveResult.Ready -> {
                        resolved.url
                    }

                    is ResolveResult.WaitForServer -> {
                        // Bug 4 fix: server is transcoding. Write WAITING_FOR_SERVER and exit cleanly.
                        // SSE transcode.complete handler (SSEEventProcessor.handleTranscodeComplete) will
                        // re-enqueue the worker via repository.resumeForAudioFile when ready.
                        repository.markWaitingForServer(audioFileId, resolved.transcodeJobId)
                        return@withContext
                    }
                }

            val destPath = fileManager.getAudioFilePath(bookId, audioFileId, filename, isTemp = false)
            val tempPath = fileManager.getAudioFilePath(bookId, audioFileId, filename, isTemp = true)

            // Resume support: if a partial tempFile exists, send Range header.
            val startByte =
                if (SystemFileSystem.exists(tempPath)) {
                    SystemFileSystem.metadataOrNull(tempPath)?.size ?: 0L
                } else {
                    0L
                }

            httpClient
                .prepareGet(url) {
                    if (startByte > 0) {
                        header(HttpHeaders.Range, "bytes=$startByte-")
                        logger.debug { "Resuming download from byte $startByte" }
                    }
                }.execute { response ->
                    // HttpResponseValidator (installed by ApiClientFactory) raises typed exceptions on
                    // non-2xx; we only see successful or partial-content responses here. Status code 206
                    // is success per RFC 7233; treat it the same as 200.

                    val contentLength = response.contentLength() ?: -1L
                    val totalSize =
                        if (startByte > 0 && response.status == HttpStatusCode.PartialContent) {
                            startByte + contentLength
                        } else {
                            contentLength
                        }

                    // Update total size in DB up front so the UI shows progress against the right denominator.
                    if (totalSize > 0) {
                        repository.updateProgress(audioFileId, startByte, totalSize)
                    }

                    // Stream the body into the temp file. Append mode iff we're resuming.
                    val channel = response.bodyAsChannel()
                    val sink = SystemFileSystem.sink(tempPath, append = startByte > 0).buffered()
                    try {
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytesRead = startByte
                        var lastProgressUpdate = 0L
                        var lastProgressBytes = startByte

                        while (!channel.isClosedForRead) {
                            if (isStopped()) {
                                throw CancellationException("Download stopped")
                            }

                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            sink.write(buffer, 0, read)
                            totalBytesRead += read

                            val now = currentEpochMilliseconds()
                            val sinceLastProgress = totalBytesRead - lastProgressBytes
                            if (now - lastProgressUpdate > PROGRESS_INTERVAL_MS ||
                                sinceLastProgress >= PROGRESS_BYTES_INTERVAL
                            ) {
                                if (totalSize > 0) {
                                    repository.updateProgress(audioFileId, totalBytesRead, totalSize)
                                }
                                setProgress(totalBytesRead, totalSize)
                                lastProgressUpdate = now
                                lastProgressBytes = totalBytesRead
                            }
                        }
                    } finally {
                        sink.close()
                    }

                    // Verify size if known.
                    val writtenSize = SystemFileSystem.metadataOrNull(tempPath)?.size ?: 0L
                    if (expectedSize > 0 && writtenSize != expectedSize) {
                        SystemFileSystem.delete(tempPath)
                        throw IOException("Size mismatch: expected $expectedSize, got $writtenSize")
                    }

                    // Move temp to final destination via FileManager.
                    if (!fileManager.moveFile(tempPath, destPath)) {
                        throw IOException("Failed to move temp file to destination")
                    }

                    // Mark complete via repository.
                    repository.markCompleted(
                        audioFileId = audioFileId,
                        localPath = destPath.toString(),
                        completedAt = currentEpochMilliseconds(),
                    )
                }
        }
    }

/**
 * Resolve the correct download URL via the prepare endpoint. Returns:
 * - [ResolveResult.Ready] when transcoding is done OR not needed; caller proceeds to download.
 * - [ResolveResult.WaitForServer] when transcoding is in progress; caller writes WAITING_FOR_SERVER
 *   and exits cleanly. SSE `transcode.complete` will re-enqueue via [DownloadRepository.resumeForAudioFile].
 *
 * Phase D Bug 4 fix: previously polled for up to 30 minutes inside this function while the worker
 * showed DOWNLOADING in the UI. The polling loop was the root cause of "minutes to first byte" —
 * the worker reported active progress while actually waiting for the server. Now the worker exits
 * cleanly and the user sees WAITING_FOR_SERVER honestly.
 */
private suspend fun resolveDownloadUrl(
    bookId: String,
    audioFileId: String,
    playbackApi: PlaybackApiContract,
    playbackPreferences: PlaybackPreferences,
    capabilityDetector: AudioCapabilityDetector,
): ResolveResult {
    val capabilities = capabilityDetector.getSupportedCodecs()
    val spatial = playbackPreferences.getSpatialPlayback()
    val result = playbackApi.preparePlayback(bookId, audioFileId, capabilities, spatial)

    if (result !is AppResult.Success) {
        logger.warn { "Prepare call failed for $audioFileId, falling back to original URL" }
        return ResolveResult.Ready("/api/v1/books/$bookId/audio/$audioFileId")
    }

    val response = result.data

    // Bug 4 fix: if transcode is in progress, write WAITING_FOR_SERVER and exit. SSE handler
    // (SSEEventProcessor.handleTranscodeComplete) will re-enqueue via repository.resumeForAudioFile.
    if (!response.ready && response.transcodeJobId != null) {
        logger.info {
            "Transcoding in progress for $audioFileId (jobId=${response.transcodeJobId}); " +
                "writing WAITING_FOR_SERVER and exiting cleanly. SSE will re-enqueue when ready."
        }
        return ResolveResult.WaitForServer(response.transcodeJobId)
    }

    logger.debug { "Using ${response.variant} variant for $audioFileId (codec: ${response.codec})" }
    return ResolveResult.Ready(relativizeUrl(response.streamUrl))
}

/** Strip an absolute server URL prefix off [streamUrl] so we always pass a relative URL to Ktor. */
internal fun relativizeUrl(streamUrl: String): String =
    when {
        streamUrl.startsWith("/") -> {
            streamUrl
        }

        streamUrl.startsWith("http") -> {
            val pathStart = streamUrl.indexOf('/', startIndex = "https://".length)
            if (pathStart > 0) streamUrl.substring(pathStart) else "/$streamUrl"
        }

        else -> {
            "/$streamUrl"
        }
    }
