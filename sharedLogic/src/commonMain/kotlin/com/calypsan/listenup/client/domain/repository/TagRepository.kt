@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.core.TagId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for tag lifecycle and observation.
 *
 * Tags are global (cross-user) content descriptors applied to books by curators.
 * Each tag has a URL-safe [Tag.slug] derived from its display name at creation time;
 * the slug is the stable identity — it never changes on rename.
 *
 * **Observation methods** read from Room (offline-first, reactive). **Mutation methods**
 * delegate to the [com.calypsan.listenup.api.TagService] RPC; the SSE sync engine
 * propagates the server-committed change back into Room, which triggers reactive UI updates.
 *
 * Mutation methods are the primary write path — no optimistic Room writes. The sync
 * engine is the single path from server state to Room.
 */
interface TagRepository {
    // ── Observation (Room-backed, offline-first) ──────────────────────────────

    /**
     * Observe all non-tombstoned tags ordered by name ascending.
     *
     * @return Flow emitting the current list of live tags; re-emits on any tag change.
     */
    fun observeAllTags(): Flow<List<Tag>>

    /**
     * Alias for [observeAllTags] retained for backward compatibility with existing callers.
     * Prefer [observeAllTags] in new code.
     */
    fun observeAll(): Flow<List<Tag>> = observeAllTags()

    /**
     * Observe all non-tombstoned tags currently applied to the given book.
     *
     * Excludes both tombstoned tags and tombstoned junction rows so removals
     * surface reactively without an explicit refresh.
     *
     * @param bookId The string book ID.
     * @return Flow emitting the live tag list for the book; re-emits on any change.
     */
    fun observeTagsForBook(bookId: String): Flow<List<Tag>>

    /**
     * Look up a non-tombstoned tag by its URL-safe slug in the local Room database.
     *
     * Slug lookups are always case-exact (slugs are already normalized to lowercase
     * at creation time). Offline-safe — does not require a network connection.
     *
     * @param slug The slug to look up, e.g. `"sci-fi"`.
     * @return [AppResult.Success] containing the tag, or `null` inside Success when not found.
     */
    suspend fun getTagBySlug(slug: String): AppResult<Tag?>

    /**
     * Observe a single tag by its ID, or emit `null` when the tag is absent or tombstoned.
     *
     * @param id The tag ID.
     * @return Flow emitting the tag or null.
     */
    fun observeById(id: String): Flow<Tag?>

    /**
     * Observe the set of book IDs that have the given tag applied (live junction rows only).
     *
     * @param tagId The tag ID.
     * @return Flow emitting list of book ID strings; re-emits on junction changes.
     */
    fun observeBookIdsForTag(tagId: String): Flow<List<String>>

    /**
     * Aggregate book count + total length for [tagId] over live books.
     *
     * Dispatches through [com.calypsan.listenup.api.TagService] — not Room-backed, since total
     * book length isn't mirrored locally.
     *
     * @param tagId The tag ID.
     * @return [AppResult.Success] with the aggregate stats, or typed failure.
     */
    suspend fun getTagStats(tagId: TagId): AppResult<FacetStats>

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    /**
     * Apply the tag with display name [name] to [bookId].
     *
     * Find-or-create semantics: if a non-deleted tag whose slug matches
     * `TagSlug.normalize(name)` already exists it is reused; otherwise a new tag
     * is created. The junction row is upserted (re-adding a previously removed tag
     * clears its `deleted_at` and bumps the revision).
     *
     * The server emits SSE events for the new or updated tag and junction row so
     * connected clients' Room databases update reactively via the sync engine.
     *
     * @param bookId The raw book ID string.
     * @param name The tag display name (will be slugified server-side).
     * @return [AppResult.Success] containing the tag aggregate, or typed failure.
     */
    suspend fun addTagToBook(
        bookId: String,
        name: String,
    ): AppResult<Tag>

    /**
     * Remove the association between [bookId] and [tagId] by soft-deleting the junction row.
     *
     * Idempotent: calling this on an already-removed junction row returns Success.
     *
     * @param bookId The raw book ID string.
     * @param tagId The tag ID to disassociate.
     * @return [AppResult.Success] with Unit on success, or typed failure.
     */
    suspend fun removeTagFromBook(
        bookId: String,
        tagId: String,
    ): AppResult<Unit>

    /**
     * Remove the association between [bookId] and the tag identified by [tagSlug]/[tagId].
     *
     * Convenience overload kept for callers that supply both slug and id.
     * Delegates to [removeTagFromBook(bookId, tagId)].
     *
     * @param bookId The raw book ID string.
     * @param tagSlug The tag slug (unused in the RPC call — the server uses [tagId]).
     * @param tagId The tag ID to disassociate.
     */
    suspend fun removeTagFromBook(
        bookId: String,
        tagSlug: String,
        tagId: String,
    ): AppResult<Unit> = removeTagFromBook(bookId, tagId)

    /**
     * Update the display name of the tag identified by [tagId] to [newName].
     *
     * Slug is intentionally preserved — only the display name changes.
     *
     * @param tagId The tag ID.
     * @param newName The new display name.
     * @return [AppResult.Success] with the updated tag, or typed failure.
     */
    suspend fun renameTag(
        tagId: String,
        newName: String,
    ): AppResult<Tag>

    /**
     * Delete the tag identified by [tagId] and cascade-soft-delete all of its junction rows.
     *
     * @param tagId The tag ID.
     * @return [AppResult.Success] with Unit on success, or typed failure.
     */
    suspend fun deleteTag(tagId: String): AppResult<Unit>
}
