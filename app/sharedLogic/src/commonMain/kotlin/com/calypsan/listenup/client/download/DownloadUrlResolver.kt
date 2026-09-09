package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Resolve the signed, **relative** download URLs for *every* audio file of [bookId] in ONE
 * `prepare()` round-trip, keyed by `fileId`.
 *
 * One `prepare()` response already carries every file's signed URL, so a per-file call is pure
 * waste: a 40-file book used to make 40 RPCs (and 40 token refreshes) against a self-hosted
 * server to learn 40 facts it was told on the first call.
 *
 * @return [AppResult.Success] with `fileId -> signed relative URL`, or the `prepare()` failure.
 */
internal suspend fun resolveSignedDownloadUrls(
    bookId: String,
    prepareRepository: PlaybackPrepareRepository,
): AppResult<Map<String, String>> =
    when (val rpcResult = prepareRepository.prepare(BookId(bookId))) {
        is AppResult.Failure -> {
            logger.warn { "prepare() failed for book=$bookId: ${rpcResult.error.message}" }
            rpcResult
        }

        is AppResult.Success -> {
            AppResult.Success(rpcResult.data.audioFiles.associate { it.fileId to it.url })
        }
    }

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
    when (val urls = resolveSignedDownloadUrls(bookId, prepareRepository)) {
        is AppResult.Failure -> {
            urls
        }

        is AppResult.Success -> {
            urls.data[audioFileId]?.let {
                // The URL itself is a live credential (HMAC-signed query) — log the id, never the URL.
                logger.debug { "Resolved signed download URL for $audioFileId" }
                AppResult.Success(it)
            }
                ?: run {
                    logger.warn { "prepare() response for book=$bookId is missing audioFileId=$audioFileId" }
                    AppResult.Failure(
                        DownloadError.DownloadFailed(
                            debugInfo = "prepare() response for book=$bookId missing audioFileId=$audioFileId",
                        ),
                    )
                }
        }
    }
