package com.calypsan.listenup.api.resources

import com.calypsan.listenup.core.BookId
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

    /**
     * `GET /api/v1/books/{id}/cover` — serves a book's cover image bytes. The
     * cover is either a filesystem image in the library or artwork embedded in
     * the book's audio file; the route resolves and serves whichever applies.
     * Responds 200 with the image bytes on success, 404 when the book is
     * absent or has no cover. The optional [v] query parameter is a cache
     * buster — clients pass the cover hash so a changed cover bypasses any
     * intermediary cache. Requires JWT authentication.
     */
    @Resource("{id}/cover")
    class Cover(
        val parent: BookResources = BookResources(),
        val id: BookId,
        val v: String? = null,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.setBookContributors] —
     * `PUT /api/v1/books/{id}/contributors` replaces the full contributor list
     * for a book. Body is a JSON array of [com.calypsan.listenup.api.dto.BookContributorInput].
     * Responds 204 on success, 404 when no book with the given id exists,
     * 400 when the input fails server-side validation. Requires JWT authentication.
     */
    @Resource("{id}/contributors")
    class Contributors(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.setBookSeries] —
     * `PUT /api/v1/books/{id}/series` replaces the full series list for a book.
     * Body is a JSON array of [com.calypsan.listenup.api.dto.BookSeriesInput].
     * Responds 204 on success, 404 when no book with the given id exists,
     * 400 when the input fails server-side validation. Requires JWT authentication.
     */
    @Resource("{id}/series")
    class Series(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )
}
