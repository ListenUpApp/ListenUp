package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.MetadataError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Downloads an image from an external URL to a local path. Writes to a sibling
 * temp file first, then atomic-renames into place — readers never see a
 * half-written file. The destination directory must already exist.
 *
 * Returns the raw bytes so the caller can reuse them without re-reading from
 * disk. The temp file is always cleaned up on failure.
 *
 * Every fetch is guarded by [SafeCoverUrl]: the initial URL AND every redirect hop are
 * re-validated (redirects are followed manually, not via Ktor's automatic `HttpRedirect`, so a
 * public host that 302s to an internal one is caught before the follow-up request is made), and
 * the response body is read as a capped stream rather than buffered unbounded — a hostile or
 * misbehaving server can't exhaust server memory through this path.
 */
class ImageStorage(
    private val httpClient: HttpClient,
    private val maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
) {
    // Redirects are followed manually below (see fetchCapped) so each hop's Location target can
    // be re-validated by SafeCoverUrl before being followed — Ktor's automatic HttpRedirect would
    // resolve the whole chain before this class ever sees the intermediate hosts.
    private val redirectlessClient = httpClient.config { followRedirects = false }

    /**
     * Fetches [url] and returns the raw bytes without writing to disk.
     *
     * Used by [com.calypsan.listenup.server.api.BookMetadataApplier] to feed
     * enriched-cover bytes through [com.calypsan.listenup.server.cover.CoverImageStore],
     * which handles validation, placement, and the managed-path record.
     *
     * @throws UnsafeCoverUrlException if [url] or a redirect hop fails [SafeCoverUrl]
     * @throws CoverTooLargeException if the response exceeds [maxBytes]
     * @throws Exception on network failure
     */
    suspend fun downloadBytes(url: String): ByteArray = fetchCapped(url)

    /**
     * Downloads [url] and writes the bytes to [destination].
     *
     * @param url the remote image URL
     * @param destination absolute [Path] to the target file
     * @return the image bytes
     * @throws UnsafeCoverUrlException if [url] or a redirect hop fails [SafeCoverUrl]
     * @throws CoverTooLargeException if the response exceeds [maxBytes]
     * @throws Exception on download or filesystem failure (after deleting the
     *   temp file if one was created)
     */
    suspend fun download(
        url: String,
        destination: Path,
    ): ByteArray {
        val bytes = fetchCapped(url)
        writeBytes(bytes, destination)
        return bytes
    }

    /**
     * Validates [url], follows redirects up to [MAX_REDIRECT_HOPS] (re-validating each Location
     * target before following it), and reads the terminal response as a size-capped stream.
     */
    private suspend fun fetchCapped(url: String): ByteArray {
        var target = url
        repeat(MAX_REDIRECT_HOPS + 1) {
            SafeCoverUrl.validate(target)?.let { throw UnsafeCoverUrlException(it) }
            val response = redirectlessClient.get(target)
            if (response.status.value !in REDIRECT_STATUS_RANGE) {
                return readCapped(response)
            }
            val location =
                response.headers[HttpHeaders.Location]
                    ?: return readCapped(response)
            target = URLBuilder(target).takeFrom(location).buildString()
        }
        val error = SafeCoverUrl.validate(target) ?: MetadataError.UnsafeUrl(debugInfo = "too many redirects")
        throw UnsafeCoverUrlException(error)
    }

    /** Reads [response]'s body as a stream, aborting once more than [maxBytes] have arrived. */
    private suspend fun readCapped(response: HttpResponse): ByteArray {
        val channel = response.bodyAsChannel()
        val buffer = Buffer()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read == -1) break
            buffer.write(chunk, 0, read)
            if (buffer.size > maxBytes) {
                throw CoverTooLargeException("download exceeded $maxBytes bytes")
            }
        }
        return buffer.readByteArray()
    }

    /**
     * Writes [bytes] to [destination] via a sibling temp file + atomic rename — readers never see a
     * half-written file. The destination directory must already exist. Cleans up the temp on failure.
     */
    fun writeBytes(
        bytes: ByteArray,
        destination: Path,
    ) {
        val tmp = Path(destination.parent!!.toString(), "${destination.name}.tmp")
        try {
            SystemFileSystem.sink(tmp).buffered().use { sink ->
                sink.write(bytes)
            }
            SystemFileSystem.atomicMove(tmp, destination)
        } catch (e: Throwable) {
            SystemFileSystem.delete(tmp, mustExist = false)
            throw e
        }
    }

    companion object {
        /**
         * Shared ceiling for cover and contributor-photo downloads. Larger than either local
         * upload cap (`COVER_MAX_BYTES` 10 MiB, `AVATAR_MAX_BYTES` 5 MiB) so it never rejects a
         * legitimate image before the type-specific store gets to validate it — this is purely a
         * memory-exhaustion guard against an oversized or hostile response.
         */
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 10L * 1024 * 1024

        private const val MAX_REDIRECT_HOPS = 5
        private const val READ_CHUNK_BYTES = 8192
        private val REDIRECT_STATUS_RANGE = 300..399
    }
}

/** Thrown by [ImageStorage] when a fetch's response exceeds its configured byte ceiling. */
class CoverTooLargeException(
    message: String,
) : Exception(message)
