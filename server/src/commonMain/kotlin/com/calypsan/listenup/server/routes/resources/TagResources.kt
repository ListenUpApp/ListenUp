package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of the tag-scoped routes in [com.calypsan.listenup.api.TagService].
 * All routes live under `/api/v1/tags` and require JWT authentication.
 *
 * Book-scoped tag routes (add, remove, list by book) are defined in
 * [BookTagsResources].
 */
@Resource("/api/v1/tags")
class TagResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.TagService.listTags] —
     * `GET /api/v1/tags` returns all non-deleted tags ordered by book count
     * descending, then name ascending, each with a live book count.
     */
    @Resource("")
    class List(
        val parent: TagResources = TagResources(),
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.TagService.getTagBySlug] —
     * `GET /api/v1/tags/by-slug/{slug}` returns the tag with the given slug,
     * or 404 when no non-deleted tag with that slug exists.
     */
    @Resource("by-slug/{slug}")
    class BySlug(
        val parent: TagResources = TagResources(),
        /** URL-safe slug identifying the tag (e.g. `sci-fi`). */
        val slug: String,
    )

    /**
     * REST mirror for per-tag operations:
     * - `PATCH /api/v1/tags/{tagId}` (body: new name) →
     *   [com.calypsan.listenup.api.TagService.renameTag]
     * - `DELETE /api/v1/tags/{tagId}` →
     *   [com.calypsan.listenup.api.TagService.deleteTag]
     *   (cascade-soft-deletes all book_tags junction rows)
     */
    @Resource("{tagId}")
    class Detail(
        val parent: TagResources = TagResources(),
        /** Tag id string (UUIDv7 at the storage layer). */
        val tagId: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.TagService.listBooksForTag] —
     * `GET /api/v1/tags/{tagId}/books?limit=N` returns up to [limit] book IDs
     * tagged with [tagId]. [limit] is clamped server-side to `1..1000`.
     */
    @Resource("{tagId}/books")
    class Books(
        val parent: TagResources = TagResources(),
        /** Tag id string. */
        val tagId: String,
        /** Maximum number of book IDs to return; clamped to `1..1000`. Default 100. */
        val limit: Int = 100,
    )
}

/**
 * REST mirror of the book-scoped tag routes in [com.calypsan.listenup.api.TagService].
 * All routes live under `/api/v1/books/{bookId}/tags` and require JWT authentication.
 *
 * Tag-scoped routes (list all tags, lookup by slug, rename, delete) are defined in
 * [TagResources].
 */
@Resource("/api/v1/books/{bookId}/tags")
class BookTagsResources(
    /** Book id string. */
    val bookId: String,
) {
    /**
     * REST mirror for book-scoped tag collection operations:
     * - `GET /api/v1/books/{bookId}/tags` →
     *   [com.calypsan.listenup.api.TagService.listTagsForBook]
     * - `POST /api/v1/books/{bookId}/tags` (body: tag name) →
     *   [com.calypsan.listenup.api.TagService.addTagToBook]
     */
    @Resource("")
    class Collection(
        val parent: BookTagsResources,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.TagService.removeTagFromBook] —
     * `DELETE /api/v1/books/{bookId}/tags/{tagId}` soft-deletes the junction
     * row linking [parent].bookId to [tagId]. Idempotent.
     */
    @Resource("{tagId}")
    class Detail(
        val parent: BookTagsResources,
        /** Tag id string of the tag to detach from the book. */
        val tagId: String,
    )
}
