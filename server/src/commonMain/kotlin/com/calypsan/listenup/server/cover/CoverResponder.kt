package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.services.BookRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes

/**
 * Serves a book's cover image bytes for `GET /api/v1/books/{id}/cover`.
 *
 * Concentrates the cover-serving logic — path resolution, cache headers, variant selection — into
 * one focused collaborator so the book route stays mechanical glue. What a cover physically *is*
 * (a file on disk, or artwork inside an audio file) is [CoverContentResolver]'s question, not this
 * class's.
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
 * Every failure mode that isn't a successful image — missing book, missing file, unparseable audio,
 * artwork-less audio — resolves to a plain 404. The route never 500s on an absent or unreadable
 * cover.
 *
 * The constructor is `internal` — it depends on the `internal` [EmbeddedMetadataParser] through
 * [CoverContentResolver], so only the `server` module's Koin wiring builds an instance; the class
 * itself stays public so route signatures can name it.
 *
 * @param repository resolves a book id to its [CoverInfo].
 * @param content resolves that [CoverInfo] to the cover's actual bytes.
 * @param derivatives the on-demand store backing `?w=`.
 */
class CoverResponder internal constructor(
    private val repository: BookRepository,
    private val content: CoverContentResolver,
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
        val bytes = derivatives.derivative(hash, rung) { content.content(id, info)?.bytes } ?: return false

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
        val resolved = content.content(id, info)
        if (resolved == null) {
            // The DB still records a cover, but the file vanished since the scan — a 404, not a 500.
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(resolved.bytes, resolved.contentType)
    }

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
