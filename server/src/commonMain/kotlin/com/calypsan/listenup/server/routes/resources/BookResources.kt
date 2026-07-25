package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.BookId
import io.ktor.resources.Resource

/**
 * Path parent for the book blob resources below. Has no handler of its own — server-side
 * book search was removed along with its FTS5 indexes, so `/api/v1/books` answers nothing;
 * this class exists only so [Cover] and [Document] can nest under it.
 */
@Resource("/api/v1/books")
class BookResources(
    val q: String? = null,
    val limit: Int = 50,
) {
    /**
     * Cover bytes for one book — `GET/PUT/DELETE /api/v1/books/{id}/cover`.
     *
     * Blobs travel over plain HTTP, never the RPC channel: a cacheable GET stays CDN- and
     * range-request-friendly, and an upload streams as multipart instead of being framed into a
     * JSON-RPC message.
     */
    @Resource("{id}/cover")
    class Cover(
        val parent: BookResources = BookResources(),
        val id: BookId,
        val v: String? = null,
    )

    /**
     * `GET /api/v1/books/{id}/documents/{docId}` — serves the bytes of a supplementary
     * document (PDF/ebook) that ships with the book. Responds 200 with the file bytes
     * (byte-range/resume via `PartialContent`) on success, 304 when the `If-None-Match`
     * ETag matches the document's content hash, and 404 when the book is inaccessible or
     * the document row/file is absent (never 403 — an inaccessible book is
     * indistinguishable from an absent one). Requires JWT authentication.
     */
    @Resource("{id}/documents/{docId}")
    class Document(
        val parent: BookResources = BookResources(),
        val id: BookId,
        val docId: String,
    )
}
