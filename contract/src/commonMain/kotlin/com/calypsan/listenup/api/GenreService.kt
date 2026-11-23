package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MergeReceiptId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for genre access and curator-edit mutations.
 *
 * Two surface categories:
 * - **Observation** — [browseBooks], [getGenreStats] and [getGenreBySlug] are safe to call
 *   repeatedly. Clients read the genre tree itself from their local Room mirror.
 * - **Curator admin** — [createGenre], [updateGenre], [deleteGenre], [moveGenre], [mergeGenres]
 *   mutate the genre taxonomy. The sync firehose delivers the authoritative payload to all connected
 *   clients; affected books are re-upserted so their `BookSyncPayload.genres` reflects the change.
 */
@Rpc
interface GenreService {
    // ── Observation ─────────────────────────────────────────────────────────

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

    /**
     * Lists the open (not yet undone) merges into [target], newest first — the "Merged into this"
     * section of the categories admin. Merges made before receipts existed are not listed and
     * cannot be undone. Requires the caller's `canEdit` flag.
     */
    suspend fun listMergeReceipts(target: GenreId): AppResult<List<MergeReceipt>>

    /**
     * Undoes the merge recorded by [receiptId]: revives the merged-away genre under its original id
     * (beneath its old parent's current path, or at the top level when that parent is gone), links
     * back every merged book still exactly as the merge left it, re-points each moved alias that
     * still points at the surviving genre, and leaves every later change alone.
     *
     * Returns [com.calypsan.listenup.api.error.GenreError.MergeReceiptNotFound],
     * [com.calypsan.listenup.api.error.GenreError.MergeAlreadyUndone],
     * [com.calypsan.listenup.api.error.GenreError.MergeTargetGone], or
     * [com.calypsan.listenup.api.error.GenreError.MergeSourceNameTaken] (a live genre holds the
     * name now). Requires the caller's `canEdit` flag.
     */
    suspend fun undoGenreMerge(receiptId: MergeReceiptId): AppResult<MergeUndoResult>
}
