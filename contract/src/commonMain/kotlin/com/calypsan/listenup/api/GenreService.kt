package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for genre access and curator-edit mutations.
 *
 * Three surface categories:
 * - **Observation** — [listGenres], [getGenre], [getGenreChildren], [browseBooks] are safe to
 *   call repeatedly. Clients observe Room locally for tree reads; per-entity reads fall back
 *   here for cache-miss situations.
 * - **Curator admin** — [createGenre], [updateGenre], [deleteGenre], [moveGenre], [mergeGenres]
 *   mutate the genre taxonomy. SSE delivers the authoritative payload back to all connected
 *   clients; affected books are re-upserted so their `BookSyncPayload.genres` reflects the change.
 * - **Unmapped curation** — [listUnmappedStrings] and [mapUnmappedToGenre] drive the curator
 *   workflow for raw genre strings the scanner couldn't resolve.
 *
 * Single-entity reads return [GenreSyncPayload] directly — the substrate-sync wire shape is the
 * canonical wire type for a genre, mirroring the [ContributorService.getContributor] precedent.
 *
 * // TODO: gate mutations by user permissions when Multi-user lands
 */
@Rpc
interface GenreService {
    // ── Observation ─────────────────────────────────────────────────────────

    /**
     * Returns a tree-shaped summary of every live genre, each row carrying its
     * book count via JOIN. Tombstoned genres are excluded. Ordered by [path] so
     * the client can render the tree without a second sort.
     */
    suspend fun listGenres(): AppResult<List<GenreSummary>>

    /**
     * Returns the genre aggregate for [id], or `null` when no live genre with
     * that id exists on the server.
     *
     * Mirrors [ContributorService.getContributor] in semantics — clients call
     * this on a cache miss against their local Room mirror.
     */
    suspend fun getGenre(id: GenreId): AppResult<GenreSyncPayload?>

    /**
     * Returns the direct children of [parentId] (one depth level only — does
     * NOT include further descendants). Empty list when [parentId] has no
     * children; [com.calypsan.listenup.api.error.GenreError.NotFound] when
     * [parentId] doesn't reference a live genre.
     */
    suspend fun getGenreChildren(parentId: GenreId): AppResult<List<GenreSyncPayload>>

    /**
     * Returns book ids linked to [genreId]. When [includeDescendants] is true,
     * also includes books linked to any genre in [genreId]'s subtree (matched
     * via `path = ? OR path LIKE ? || '/%'`). [limit] is clamped to `[1, 1000]`.
     */
    suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean = false,
        limit: Int = 100,
    ): AppResult<List<BookId>>

    /**
     * Aggregate book count + total length for [genreId], counting the whole subtree when
     * [includeDescendants].
     */
    suspend fun getGenreStats(
        genreId: GenreId,
        includeDescendants: Boolean,
    ): AppResult<FacetStats>

    /** Resolve a genre by its slug for deep-linking. Null when no live genre matches. */
    suspend fun getGenreBySlug(slug: String): AppResult<GenreSummary?>

    // ── Curator admin ───────────────────────────────────────────────────────

    /**
     * Creates a new genre under [parentId] (or as a root when [parentId] is null)
     * with the given [name] and [sortOrder]. Slug is derived deterministically
     * from [name] via `GenreSlug.normalize`.
     *
     * Returns the new genre's id on success. Returns
     * [com.calypsan.listenup.api.error.GenreError.InvalidInput] when [name] is
     * blank or slug normalization yields empty,
     * [com.calypsan.listenup.api.error.GenreError.SlugConflict] when the derived
     * slug collides with an existing live genre,
     * [com.calypsan.listenup.api.error.GenreError.NotFound] when [parentId] is
     * non-null but references a tombstoned or missing genre.
     */
    suspend fun createGenre(
        parentId: GenreId?,
        name: String,
        sortOrder: Int = 0,
    ): AppResult<GenreId>

    /**
     * PATCHes the genre identified by [id] with [patch]. Null patch fields mean
     * "don't touch." Renames preserve the genre's slug — only [GenreUpdate.name]
     * updates the display name, so renames cannot produce slug collisions.
     *
     * Returns [com.calypsan.listenup.api.error.GenreError.NotFound] when [id]
     * doesn't reference a live genre.
     */
    suspend fun updateGenre(
        id: GenreId,
        patch: GenreUpdate,
    ): AppResult<Unit>

    /**
     * Soft-deletes the genre [id] after cascading `book_genres` + `genre_aliases`
     * for the deleted genre. Refuses with
     * [com.calypsan.listenup.api.error.GenreError.HasDescendants] when the genre
     * has any live children — the operator must explicitly delete or move the
     * subtree first. Affected books are re-upserted so their
     * `BookSyncPayload.genres` reflects the loss.
     */
    suspend fun deleteGenre(id: GenreId): AppResult<Unit>

    /**
     * Moves the subtree rooted at [id] under [newParentId] (or to the root when
     * [newParentId] is null). Rewrites `path` and `depth` for every node in the
     * subtree in a single transaction; emits one `genre.Updated` per node.
     *
     * Returns [com.calypsan.listenup.api.error.GenreError.MoveSelfDescendant]
     * when [newParentId] is [id] itself or any node in [id]'s subtree (would
     * create a cycle),
     * [com.calypsan.listenup.api.error.GenreError.SlugConflict] when the new
     * path collides with an existing live genre,
     * [com.calypsan.listenup.api.error.GenreError.NotFound] when [id] or
     * [newParentId] doesn't reference a live genre.
     */
    suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit>

    /**
     * Merges genre [source] into genre [target]:
     * - All `book_genres` rows referencing [source] are re-linked to [target]
     *   (INSERT-OR-IGNORE so books already linked to both don't duplicate).
     * - All `genre_aliases` rows referencing [source] are re-pointed to [target]
     *   (preserves curator mapping work).
     * - [source] is soft-deleted.
     *
     * Refuses with [com.calypsan.listenup.api.error.GenreError.HasDescendants]
     * when [source] has live children. Returns
     * [com.calypsan.listenup.api.error.GenreError.MergeSelfTarget] when
     * `source == target`. Returns
     * [com.calypsan.listenup.api.error.GenreError.NotFound] when either is
     * missing or tombstoned.
     */
    suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit>

    // ── Unmapped curation ───────────────────────────────────────────────────

    /**
     * Returns one row per distinct raw string in `pending_book_genres`,
     * aggregated by string. Ordered by `bookCount DESC, rawString ASC` so
     * high-impact strings surface to the curator first.
     */
    suspend fun listUnmappedStrings(): AppResult<List<UnmappedStringSummary>>

    /**
     * Maps the raw string [rawString] to live genre [genreId]:
     * - Adds (or replaces) a `genre_aliases` row binding [rawString] to [genreId].
     * - For every book currently in `pending_book_genres` with [rawString],
     *   inserts a `book_genres` row (insert-or-ignore) and removes the pending row.
     * - All affected books are re-upserted.
     *
     * Returns [com.calypsan.listenup.api.error.GenreError.NotFound] when [genreId]
     * doesn't reference a live genre. Returns
     * [com.calypsan.listenup.api.error.GenreError.UnmappedStringNotFound] when no
     * `pending_book_genres` row matches [rawString].
     */
    suspend fun mapUnmappedToGenre(
        rawString: String,
        genreId: GenreId,
    ): AppResult<Unit>
}
