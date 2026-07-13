package com.calypsan.listenup.server.routes.resources

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
     *
     * Also serves [com.calypsan.listenup.api.BookService.updateBook] —
     * `PATCH /api/v1/books/{id}` applies a [com.calypsan.listenup.api.dto.BookUpdate]
     * patch and responds 204 on success.
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
     *
     * Also serves [com.calypsan.listenup.api.BookService.deleteBookCover] —
     * `DELETE /api/v1/books/{id}/cover` removes the book's cover (DB null-out
     * + post-commit file delete) and responds 204 on success.
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

    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.setBookGenres] —
     * `PUT /api/v1/books/{id}/genres` replaces the full genre list for a book.
     * Body is a JSON array of [com.calypsan.listenup.api.dto.BookGenreInput].
     * Unlike contributors/series, genres are NOT auto-created — unknown
     * `genreId` values respond 400. Responds 204 on success, 404 when no book
     * with the given id exists, 400 when the input fails server-side validation.
     * Requires JWT authentication.
     */
    @Resource("{id}/genres")
    class Genres(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.setBookChapters] —
     * `PUT /api/v1/books/{id}/chapters` replaces the full chapter list for a book
     * and marks provenance USER. Body is a JSON array of
     * [com.calypsan.listenup.api.dto.ChapterInput]. Responds 204 on success,
     * 404 when no book with the given id exists, 400 when the set fails
     * server-side validation. Requires JWT authentication.
     */
    @Resource("{id}/chapters")
    class Chapters(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.BookService.setBookTierLabels] —
     * `PUT /api/v1/books/{id}/chapter-tiers` renames the book's two chapter-grouping tiers.
     * Body is a [com.calypsan.listenup.api.dto.TierLabelsInput]. Responds 204 on success,
     * 404 when no book with the given id exists, 400 when a non-null label fails validation.
     * Requires JWT authentication.
     */
    @Resource("{id}/chapter-tiers")
    class ChapterTiers(
        val parent: BookResources = BookResources(),
        val id: BookId,
    )
}
