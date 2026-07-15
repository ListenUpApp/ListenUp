package com.calypsan.listenup.server.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Downloads an image from an external URL to a local path. Writes to a sibling
 * temp file first, then atomic-renames into place — readers never see a
 * half-written file. The destination directory must already exist.
 *
 * Returns the raw bytes so the caller can reuse them without re-reading from
 * disk. The temp file is always cleaned up on failure.
 */
class ImageStorage(
    private val httpClient: HttpClient,
) {
    /**
     * Fetches [url] and returns the raw bytes without writing to disk.
     *
     * Used by [com.calypsan.listenup.server.api.BookMetadataApplier] to feed
     * enriched-cover bytes through [com.calypsan.listenup.server.cover.CoverImageStore],
     * which handles validation, placement, and the managed-path record.
     *
     * @throws Exception on network failure
     */
    suspend fun downloadBytes(url: String): ByteArray = httpClient.get(url).bodyAsBytes()

    /**
     * Downloads [url] and writes the bytes to [destination].
     *
     * @param url the remote image URL
     * @param destination absolute [Path] to the target file
     * @return the image bytes
     * @throws Exception on download or filesystem failure (after deleting the
     *   temp file if one was created)
     */
    suspend fun download(
        url: String,
        destination: Path,
    ): ByteArray {
        val bytes = httpClient.get(url).bodyAsBytes()
        writeBytes(bytes, destination)
        return bytes
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
}
