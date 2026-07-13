@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
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
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * Result of [resolveDownloadUrl]. Only [Ready] — the [WaitForServer] variant has been removed
 * because the RPC [com.calypsan.listenup.api.PlaybackService.prepare] always returns signed URLs
 * for files that are ready; there is no transcoding-in-progress state in the new path.
 */
internal sealed interface ResolveResult {
    /** Server is ready to stream the audio at [url]; caller proceeds with the download. */
    data class Ready(
        val url: String,
    ) : ResolveResult
}

private const val PROGRESS_INTERVAL_MS = 500L
private const val BUFFER_SIZE = 8 * 1024
private const val PROGRESS_BYTES_INTERVAL = 256 * 1024L // 256KB — emit progress at least every quarter MB

/**
 * Core download logic for a single audio file, extracted from [DownloadWorker] so it can be
 * driven from commonTest/jvmTest without WorkManager or an Android Context.
 *
 * Returns [AppResult.Success] when the file is fully downloaded. Returns
 * [AppResult.Failure] with a typed [com.calypsan.listenup.api.error.AppError] on any failure;
 * [kotlinx.coroutines.CancellationException] still propagates so callers can distinguish
 * cancellation from failure.
 *
 * Features:
 * - Signed URL resolution via [PlaybackService.prepare]
 * - Resume support (Range headers)
 * - Progress updates via [setProgress] lambda
 * - Cancellation handling via [isStopped] lambda
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
    prepareRepository: PlaybackPrepareRepository,
    isStopped: () -> Boolean = { false },
    setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    yieldToPlayback: suspend () -> Unit = {},
): AppResult<Unit> =
    suspendRunCatching {
        withContext(IODispatcher) {
            // Resolve the signed download URL from PlaybackService.prepare.
            // The download HTTP client uses a defaultRequest base URL, so the URL must be
            // RELATIVE (e.g. /api/v1/audio/{book}/{fileId}?u=&exp=&sig=).
            val resolved =
                resolveDownloadUrl(
                    bookId = bookId,
                    audioFileId = audioFileId,
                    prepareRepository = prepareRepository,
                )
            val url = resolved.url

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

                    // Range-honored guard: we only send a Range header when startByte > 0. A 200 OK back
                    // means the server (or an intervening proxy) IGNORED the Range and is streaming the
                    // WHOLE body. Appending that full body onto the partial temp would yield a corrupt
                    // startByte+fullSize file. Truncate the temp and restart cleanly from byte 0 instead.
                    val serverIgnoredRange = startByte > 0 && response.status != HttpStatusCode.PartialContent
                    if (serverIgnoredRange) {
                        logger.warn {
                            "Server ignored Range for $audioFileId (status ${response.status.value}); " +
                                "restarting from byte 0 to avoid a corrupt append"
                        }
                        SystemFileSystem.delete(tempPath)
                    }
                    val effectiveStartByte = if (serverIgnoredRange) 0L else startByte

                    val contentLength = response.contentLength() ?: -1L
                    val totalSize =
                        if (effectiveStartByte > 0 && response.status == HttpStatusCode.PartialContent) {
                            effectiveStartByte + contentLength
                        } else {
                            contentLength
                        }

                    // Update total size in DB up front so the UI shows progress against the right denominator.
                    if (totalSize > 0) {
                        // Progress write is best-effort UI feedback; a dropped update is corrected by the next tick.
                        val _ = repository.updateProgress(audioFileId, effectiveStartByte, totalSize)
                    }

                    // Stream the body into the temp file. Append mode iff we're genuinely resuming.
                    val channel = response.bodyAsChannel()
                    val sink = SystemFileSystem.sink(tempPath, append = effectiveStartByte > 0).buffered()
                    try {
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytesRead = effectiveStartByte
                        var lastProgressUpdate = 0L
                        var lastProgressBytes = effectiveStartByte

                        while (!channel.isClosedForRead) {
                            if (isStopped()) {
                                throw CancellationException("Download stopped")
                            }
                            // Soft-pause between chunks while playback is buffering a stream, so the
                            // stream gets the bandwidth. Keeps the connection + partial file warm
                            // (no cancel). The caller bounds how long this can suspend.
                            yieldToPlayback()

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
                                    val _ = repository.updateProgress(audioFileId, totalBytesRead, totalSize)
                                }
                                setProgress(totalBytesRead, totalSize)
                                lastProgressUpdate = now
                                lastProgressBytes = totalBytesRead
                            }
                        }
                    } finally {
                        sink.close()
                    }

                    // Integrity gate. Prefer the caller's [expectedSize]; fall back to the server's
                    // advertised total ([totalSize], from Content-Length, adjusted for resume). A
                    // download we cannot verify against ANY known size is a FAILURE — a stream that
                    // closes early without a transport error would otherwise be atomicMove'd and
                    // markCompleted'd at a bogus 100%.
                    val writtenSize = SystemFileSystem.metadataOrNull(tempPath)?.size ?: 0L
                    val verificationTarget = if (expectedSize > 0) expectedSize else totalSize
                    if (verificationTarget > 0) {
                        if (writtenSize != verificationTarget) {
                            SystemFileSystem.delete(tempPath)
                            throw IOException(
                                "Size mismatch for $audioFileId: expected $verificationTarget, got $writtenSize",
                            )
                        }
                    } else {
                        SystemFileSystem.delete(tempPath)
                        throw IOException(
                            "Download for $audioFileId is unverifiable (no expected size and no " +
                                "Content-Length); failing rather than finalizing a possibly-truncated file",
                        )
                    }

                    // Move temp to final destination via FileManager.
                    if (!fileManager.moveFile(tempPath, destPath)) {
                        throw IOException("Failed to move temp file to destination")
                    }

                    // Mark complete via repository. The file is already on disk; if the DB write fails
                    // the row is still DOWNLOADING, so surface it as a FAILURE (B10c) rather than
                    // logging "complete" over a lying state — the worker then records FAILED honestly.
                    when (
                        val completed =
                            repository.markCompleted(
                                audioFileId = audioFileId,
                                localPath = destPath.toString(),
                                completedAt = currentEpochMilliseconds(),
                            )
                    ) {
                        is AppResult.Success -> {
                            Unit
                        }

                        is AppResult.Failure -> {
                            logger.error {
                                "Download finished on disk but markCompleted failed for $audioFileId: " +
                                    completed.error.message
                            }
                            throw IOException(
                                "markCompleted failed after file landed for $audioFileId: ${completed.error.message}",
                            )
                        }
                    }
                }
        }
    }

/**
 * Resolve the correct download URL via [PlaybackService.prepare], wrapping the shared
 * [resolveSignedDownloadUrl] in the [ResolveResult.Ready]/throw contract this download path expects.
 *
 * The signed URL is the **relative** path (the download HTTP client has a defaultRequest base — do
 * NOT prepend the server base URL here).
 *
 * Throws [IllegalStateException] on [AppResult.Failure] or when the fileId is absent from the
 * response; [suspendRunCatching] in [downloadAudioFile] catches it and routes to
 * [AppResult.Failure].
 */
private suspend fun resolveDownloadUrl(
    bookId: String,
    audioFileId: String,
    prepareRepository: PlaybackPrepareRepository,
): ResolveResult.Ready =
    when (val resolved = resolveSignedDownloadUrl(bookId, audioFileId, prepareRepository)) {
        is AppResult.Success -> {
            ResolveResult.Ready(resolved.data)
        }

        is AppResult.Failure -> {
            error("prepare() failed for book=$bookId audioFile=$audioFileId: ${resolved.error.message}")
        }
    }
