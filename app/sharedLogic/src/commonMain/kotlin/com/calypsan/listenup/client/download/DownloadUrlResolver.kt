package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Resolve the signed, **relative** download URL for [audioFileId] within [bookId] via
 * [com.calypsan.listenup.api.PlaybackService.prepare].
 *
 * The server serves audio at `GET /api/v1/audio/{bookId}/{fileId}?u=&exp=&sig=`, authenticated by
 * the signed query string — *not* a bearer token. `prepare()` mints those signed URLs; this
 * function picks the one matching [audioFileId]. The returned path is **relative** (it starts with
 * `/api/v1/audio/...`):
 * - Android's download client has a configured base URL, so it uses the path verbatim.
 * - iOS's `NSURLSession` has no base, so [com.calypsan.listenup.client.download.AppleDownloadService]
 *   prepends the server URL.
 *
 * Sharing this resolver across both platforms keeps the download path on the same server contract
 * as streaming — the previous iOS-only hardcoded `/api/v1/books/{bookId}/audio/{fileId}` route no
 * longer exists and 404s.
 *
 * @return [AppResult.Success] with the signed relative URL, or [AppResult.Failure] when `prepare()`
 *   fails or the response does not contain [audioFileId].
 */
internal suspend fun resolveSignedDownloadUrl(
    bookId: String,
    audioFileId: String,
    prepareRepository: PlaybackPrepareRepository,
): AppResult<String> =
    when (val rpcResult = prepareRepository.prepare(BookId(bookId))) {
        is AppResult.Failure -> {
            logger.warn { "prepare() failed for book=$bookId audioFile=$audioFileId: ${rpcResult.error.message}" }
            rpcResult
        }

        is AppResult.Success -> {
            val audioFile = rpcResult.data.audioFiles.firstOrNull { it.fileId == audioFileId }
            if (audioFile == null) {
                logger.warn { "prepare() response for book=$bookId is missing audioFileId=$audioFileId" }
                AppResult.Failure(
                    DownloadError.DownloadFailed(
                        debugInfo = "prepare() response for book=$bookId missing audioFileId=$audioFileId",
                    ),
                )
            } else {
                logger.debug { "Resolved signed download URL for $audioFileId: ${audioFile.url}" }
                AppResult.Success(audioFile.url)
            }
        }
    }
