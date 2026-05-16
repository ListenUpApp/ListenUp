package com.calypsan.listenup.api.resources

import com.calypsan.listenup.client.core.BookId
import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.BookService.searchBooks] —
 * `GET /api/v1/books?q=&limit=` runs a server-side FTS5 query and returns
 * matching [BookId]s in rank order. A blank [q] returns an empty list.
 * Rate-limited to 60 requests per minute per remote host. Requires JWT
 * authentication.
 *
 * Also serves as the parent resource for the nested [Detail] route.
 */
@Resource("/api/v1/books")
class BookResources(
    val q: String? = null,
    val limit: Int = 50,
) {
    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.getBook] —
     * `GET /api/v1/books/{id}` returns the full book aggregate as a
     * [com.calypsan.listenup.api.sync.BookSyncPayload]. Responds 200 on success,
     * 404 when no book with the given id exists. Requires JWT authentication.
     */
    @Resource("{id}")
    class Detail(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )
}
