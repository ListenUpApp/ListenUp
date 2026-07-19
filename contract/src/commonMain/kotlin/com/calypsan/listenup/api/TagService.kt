package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.TagSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for tag lifecycle management and observation.
 *
 * Tags are server-wide (cross-user) entities that curators apply to books.
 * Each tag has a unique slug derived from its display name at creation time;
 * the slug is the stable URL identity — it never changes on rename.
 *
 * Two surface categories:
 * - **Observation** — [listTags], [getTagBySlug], [listBooksForTag],
 *   [listTagsForBook] are safe to call repeatedly; they read state only.
 * - **Mutation** — [addTagToBook], [removeTagFromBook], [renameTag],
 *   [deleteTag] mutate server state and should be called once per user intent.
 *
 * The `book_tags` junction is a global cross-user association (curator model).
 * Per-user ACL enforcement is deferred to the Multi-user phase.
 *
 * REST mirrors are defined in `TagResources`
 * and `BookTagsResources`.
 */
@Rpc
interface TagService {
    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns all non-deleted tags ordered by book count descending, then name
     * ascending, each annotated with its live [TagSummary.bookCount].
     *
     * Book counts are computed via `LEFT JOIN COUNT(*)` on read — no
     * denormalization column; drift is impossible by construction.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun listTags(): AppResult<List<TagSummary>>

    /**
     * Returns the tag with the given [slug], or `null` inside [AppResult.Success]
     * when no non-deleted tag with that slug exists.
     *
     * Slug lookups are case-insensitive by construction because slugs are
     * normalized to lowercase at creation time.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun getTagBySlug(slug: String): AppResult<TagSummary?>

    /**
     * Returns up to [limit] book IDs tagged with [tagId], excluding any books
     * whose junction row has been soft-deleted.
     *
     * [limit] is clamped server-side to `1..1000`. Default 100.
     *
     * Callers hydrate book detail from Room for IDs already cached and call
     * `BookService.getBook` for any cache misses.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun listBooksForTag(
        tagId: TagId,
        limit: Int = 100,
    ): AppResult<List<BookId>>

    /**
     * Returns all tags currently applied to the book identified by [bookId],
     * excluding any junction rows that have been soft-deleted.
     *
     * Returns an empty list when the book exists but has no tags. Returns
     * [com.calypsan.listenup.api.error.TagError.BookNotFound] when no book
     * with the given id exists on the server.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun listTagsForBook(bookId: BookId): AppResult<List<Tag>>

    /**
     * Aggregate book count + total length for [tagId] over live books.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun getTagStats(tagId: TagId): AppResult<FacetStats>

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Applies a tag with display name [name] to the book identified by [bookId]
     * and returns the tag aggregate.
     *
     * Find-or-create semantics: if a non-deleted tag whose slug matches
     * `TagSlug.normalize(name)` already exists, that tag is reused and its
     * display name is NOT mutated. If no matching tag exists, a new one is
     * created. The junction row is upserted (re-adding a previously removed tag
     * clears its `deleted_at` and bumps the revision).
     *
     * Failures:
     * - [com.calypsan.listenup.api.error.TagError.InvalidName] when [name] is
     *   blank or normalizes to an empty slug.
     * - [com.calypsan.listenup.api.error.TagError.NameTooLong] when [name]
     *   exceeds 64 characters.
     * - [com.calypsan.listenup.api.error.TagError.BookNotFound] when no book
     *   with the given id exists.
     *
     * On success the server emits SSE events for the new or updated tag and
     * junction row so connected clients' Room databases update reactively.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun addTagToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Tag>

    /**
     * Removes the association between [bookId] and [tagId] by soft-deleting the
     * `book_tags` junction row.
     *
     * Idempotent: calling this on a junction row that is already soft-deleted
     * returns [AppResult.Success] without further writes.
     *
     * Returns [com.calypsan.listenup.api.error.TagError.BookNotFound] when no
     * book with [bookId] exists. Returns [com.calypsan.listenup.api.error.TagError.NotFound]
     * when no tag with [tagId] exists.
     *
     * On success the server emits an SSE event for the tombstoned junction row.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun removeTagFromBook(
        bookId: BookId,
        tagId: TagId,
    ): AppResult<Unit>

    /**
     * Updates the display name of the tag identified by [tagId] to [newName]
     * and returns the updated [Tag] aggregate.
     *
     * Rename semantics: only the display [Tag.name] changes — [Tag.slug] is
     * intentionally preserved so that existing URLs and client-side slug lookups
     * remain valid.
     *
     * Failures:
     * - [com.calypsan.listenup.api.error.TagError.NotFound] when no tag with
     *   the given id exists.
     * - [com.calypsan.listenup.api.error.TagError.InvalidName] when [newName]
     *   is blank or normalizes to an empty slug.
     * - [com.calypsan.listenup.api.error.TagError.NameTooLong] when [newName]
     *   exceeds 64 characters.
     *
     * On success the server emits an SSE event for the updated tag row. The
     * `tag_search` FTS5 virtual table is updated automatically via the
     * `tags_au` trigger.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun renameTag(
        tagId: TagId,
        newName: String,
    ): AppResult<Tag>

    /**
     * Deletes the tag identified by [tagId] and cascade-soft-deletes all of its
     * `book_tags` junction rows atomically inside a single transaction.
     *
     * Cascade semantics (all performed inside one `suspendTransaction`):
     * 1. Soft-delete every `book_tags` row referencing this tag.
     * 2. Soft-delete the tag row.
     * Outside the transaction: `BookSearchReindexer.reindexBookTags` is called
     * for every affected book so their `book_search.tags` columns are cleared.
     *
     * Returns [com.calypsan.listenup.api.error.TagError.NotFound] when no tag
     * with the given id exists.
     *
     * On success the server emits SSE events for all tombstoned rows.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun deleteTag(tagId: TagId): AppResult<Unit>
}
