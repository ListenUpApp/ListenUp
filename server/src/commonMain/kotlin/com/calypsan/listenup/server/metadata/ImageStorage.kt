package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.writeBytesAtomically
import io.ktor.client.HttpClient
import kotlinx.io.files.Path

/**
 * Fetches images from external URLs and writes them to local paths.
 *
 * Every fetch goes through [BoundedImageFetch], which owns the whole untrusted-URL policy: the
 * destination check, per-hop redirect re-validation, the declared-type check, and the streaming
 * byte ceiling. This class adds only the disk half — an atomic write, so readers never see a
 * half-written file and a failed write leaves no temp behind.
 */
class ImageStorage(
    httpClient: HttpClient,
    maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
) {
    private val boundedFetch = BoundedImageFetch(httpClient = httpClient, maxBytes = maxBytes)

    /**
     * Fetches [url] and returns the raw bytes without writing to disk, or a typed failure when the
     * URL, a redirect hop, the declared content type, or the response size fails the policy.
     *
     * The bytes are still *unvalidated image data* at this point — the magic-number sniff belongs
     * to [com.calypsan.listenup.server.media.ImageStore], which every caller routes them through
     * before anything reaches the filesystem.
     */
    suspend fun downloadBytes(url: String): AppResult<ByteArray> = boundedFetch.fetch(url)

    /**
     * Writes [bytes] to [destination] via a sibling temp file + atomic rename — readers never see a
     * half-written file. The destination directory must already exist. Cleans up the temp on failure.
     */
    fun writeBytes(
        bytes: ByteArray,
        destination: Path,
    ) = destination.writeBytesAtomically(bytes)

    companion object {
        /**
         * The ceiling this storage applies to a download. The value is [BoundedImageFetch]'s —
         * kept under this name because it is the one callers and specs already reach for.
         */
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = BoundedImageFetch.DEFAULT_MAX_IMAGE_BYTES
    }
}
