package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.BookRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = loggerFor<CoverResponder>()

/**
 * Serves a book's cover image bytes for `GET /api/v1/books/{id}/cover`.
 *
 * Concentrates the cover-serving logic — path resolution, embedded-artwork
 * extraction, content-type derivation — into one focused collaborator so the
 * book route stays mechanical glue. Two cover kinds (see [CoverInfo]):
 *
 *  - **Filesystem** — the image bytes are read straight off disk; the
 *    content-type comes from the file extension.
 *  - **Embedded** — artwork is extracted from the audio file via
 *    [EmbeddedMetadataParser] and cached in [cache]; the content-type comes
 *    from the artwork's own MIME field.
 *
 * **`?w=` asks for a smaller rendering**, and is the only thing that changes what a caller gets.
 * Absent, the response is byte-for-byte what it has always been — the guarantee native clients
 * depend on, since they store the full-size cover locally and want exactly that. Present, the width
 * is snapped up to a ladder rung and served from [CoverDerivatives]; anything the ladder or the
 * codec cannot answer falls back to the original rather than erroring, so a caller asking for a
 * size is never worse off than one that did not.
 *
 * ⛔ **The `ETag` distinguishes the variant** — `"<hash>"` and `"<hash>@300"` are different bytes at
 * the same URL under the same year-long `immutable` cache. A derivative that later becomes
 * *possible* for a cover that declines today (a new codec, say) must be able to reach clients, so a
 * declined request keeps the plain hash and never borrows the variant's tag.
 *
 * Every failure mode that isn't a successful image — missing book, missing
 * file, unparseable audio, artwork-less audio — resolves to a plain 404. The
 * route never 500s on an absent or unreadable cover.
 *
 * The constructor is `internal` — it depends on the `internal`
 * [EmbeddedMetadataParser], so only the `server` module's Koin wiring builds
 * an instance; the class itself stays public so route signatures can name it.
 *
 * @param repository resolves a book id to its [CoverInfo].
 * @param cache the LRU cache for extracted embedded artwork.
 * @param parser the embedded-metadata parser used to extract artwork.
 * @param derivatives the on-demand store backing `?w=`.
 */
class CoverResponder internal constructor(
    private val repository: BookRepository,
    private val cache: EmbeddedCoverCache,
    private val parser: EmbeddedMetadataParser,
    private val derivatives: CoverDerivatives,
) {
    /** Resolves [id]'s cover and writes the image bytes (or a 404) to [call]. */
    suspend fun respondCover(
        call: ApplicationCall,
        id: BookId,
    ) {
        val info = repository.coverInfo(id)
        if (info == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val hash = info.hash
        val rung =
            call.request.queryParameters[WIDTH_PARAM]
                ?.toIntOrNull()
                ?.let { derivatives.rungFor(it) }

        if (rung != null && hash != null && respondDerivative(call, id, info, hash, rung)) return
        respondOriginal(call, id, info)
    }

    /**
     * Serves the [rung]-wide rendering of [info], answering `true` when it did. `false` means the
     * codec declined and the caller should fall back to the original — the request is not failed.
     */
    private suspend fun respondDerivative(
        call: ApplicationCall,
        id: BookId,
        info: CoverInfo,
        hash: String,
        rung: Int,
    ): Boolean {
        val etag = "\"$hash@$rung\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return true
        }
        // The read of the full-size bytes only happens on a cache miss.
        val bytes = derivatives.derivative(hash, rung) { coverContent(id, info)?.bytes } ?: return false

        call.response.headers.append(HttpHeaders.ETag, etag)
        call.response.headers.append(HttpHeaders.CacheControl, CACHE_CONTROL_IMMUTABLE)
        call.respondBytes(bytes, ContentType.Image.JPEG)
        return true
    }

    private suspend fun respondOriginal(
        call: ApplicationCall,
        id: BookId,
        info: CoverInfo,
    ) {
        val etag = info.hash?.let { "\"$it\"" }
        if (etag != null) {
            if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                call.respond(HttpStatusCode.NotModified)
                return
            }
            call.response.headers.append(HttpHeaders.ETag, etag)
            call.response.headers.append(HttpHeaders.CacheControl, CACHE_CONTROL_IMMUTABLE)
        }
        val content = coverContent(id, info)
        if (content == null) {
            // The DB still records a cover, but the file vanished since the scan — a 404, not a 500.
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(content.bytes, content.contentType)
    }

    /**
     * The cover's full-size bytes and their content type, whichever kind of cover it is, or `null`
     * when they cannot be produced. One resolution point, so the derivative path and the original
     * path can never disagree about what a book's cover actually is.
     */
    private suspend fun coverContent(
        id: BookId,
        info: CoverInfo,
    ): CoverContent? =
        when (info) {
            is CoverInfo.Filesystem -> fileContent(info.path)
            is CoverInfo.Managed -> fileContent(info.path)
            is CoverInfo.Embedded -> embeddedContent(id, info.audioFilePath)
        }

    private suspend fun fileContent(path: Path): CoverContent? {
        val bytes =
            withContext(fileIoDispatcher) {
                if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) null else path.readBytes()
            }
        return bytes?.let { CoverContent(it, contentTypeForExtension(path)) }
    }

    private suspend fun embeddedContent(
        id: BookId,
        audioFilePath: Path,
    ): CoverContent? {
        val artwork =
            cache.getOrCompute(id) {
                when (val result = parser.parse(audioFilePath)) {
                    is AppResult.Success -> {
                        result.data.artwork
                    }

                    is AppResult.Failure -> {
                        log.warn { "embedded cover extraction failed for $id: ${result.error.code}" }
                        null
                    }
                }
            } ?: return null
        return CoverContent(
            artwork.bytes,
            runCatching { ContentType.parse(artwork.mime) }.getOrDefault(ContentType.Image.JPEG),
        )
    }

    /**
     * Maps a cover image file's extension to its [ContentType]. Unknown
     * extensions fall back to `image/jpeg` — the scanner only ingests
     * `png`/`jpg`/`jpeg`/`webp`, so this is defensive.
     */
    private fun contentTypeForExtension(path: Path): ContentType =
        when (
            path.name
                .substringAfterLast('.', "")
                .lowercase()
        ) {
            "png" -> ContentType.Image.PNG
            "webp" -> ContentType.parse("image/webp")
            else -> ContentType.Image.JPEG
        }

    /** A cover's bytes paired with the content type they should be served under. */
    private class CoverContent(
        val bytes: ByteArray,
        val contentType: ContentType,
    )

    private companion object {
        /** The requested display width in physical pixels; snapped up to a ladder rung. */
        const val WIDTH_PARAM = "w"

        /**
         * One-year `private` cache with `immutable` so clients never revalidate.
         * Safe because the URL is keyed by book id and the ETag is keyed by the
         * cover bytes' content hash — any cover change becomes a new ETag.
         */
        const val CACHE_CONTROL_IMMUTABLE = "private, max-age=31536000, immutable"
    }
}
