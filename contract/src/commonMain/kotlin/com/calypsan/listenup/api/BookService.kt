package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for on-demand book access and user-edit mutations.
 *
 * Two surface categories:
 * - **Observation** — [getBook], [searchBooks] are safe to call repeatedly.
 * - **Mutation** — [updateBook], [setBookContributors], [setBookSeries],
 *   [deleteBookCover] mutate server state; the sync firehose delivers the authoritative
 *   payload back to all connected clients.
 *
 * REST mirrors are defined in `BookResources`.
 *
 * // TODO: gate mutations by user permissions when Multi-user lands
 */
@Rpc
interface BookService {
    // ── Observation (existing) ───────────────────────────────────────────────

    /**
     * Returns the full book aggregate for [id], or a [com.calypsan.listenup.api.error.SyncError.NotFound]
     * failure when no such book exists on the server.
     *
     * The primary use case is a cache miss: a search result or deep link
     * references a book the client's Room database hasn't synced yet. Rather
     * than showing an error, the client calls [getBook] to fetch the aggregate
     * on demand and render immediately while sync catches up in the background.
     */
    suspend fun getBook(id: BookId): AppResult<BookSyncPayload>

    /**
     * Runs a server-side FTS5 query and returns matching [BookId]s in rank order.
     *
     * Searches across title, subtitle, description, contributor names, and series
     * names. A blank [query] returns an empty list — callers should treat blank
     * input as "no search initiated" rather than "return everything". Clients
     * hydrate book detail from Room for ids already cached, and call [getBook]
     * for any that are not.
     *
     * @param query the FTS5 search query (non-blank for a meaningful result).
     * @param limit maximum number of ids to return; clamped to the range [1, 200].
     */
    suspend fun searchBooks(
        query: String,
        limit: Int = 50,
    ): AppResult<List<BookId>>

    // ── Mutation (new in Books-C1) ───────────────────────────────────────────

    /**
     * Applies the PATCH payload [patch] to the book identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. Returns
     * [com.calypsan.listenup.api.error.BookError.NotFound] when no book with
     * the given id exists.
     *
     * On success the server emits a sync event with the updated
     * [BookSyncPayload]; clients update Room reactively.
     */
    suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit>

    /**
     * Replaces the full contributor list for the book identified by [id] with
     * [contributors]. Inputs without [BookContributorInput.id] resolve via
     * `ContributorRepository.resolveOrCreate`; unknown names create fresh
     * contributor rows in the same transaction.
     *
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. Server-side guard limits inputs to 200; overflow surfaces as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput].
     */
    suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full series list for the book identified by [id] with [series].
     * Same find-or-create semantics as [setBookContributors] for unknown names.
     */
    suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full genre list for the book identified by [id] with [genres].
     *
     * Unlike [setBookContributors] / [setBookSeries], genres are NOT auto-created.
     * Each input's [com.calypsan.listenup.api.dto.BookGenreInput.genreId] must
     * reference an existing live genre; unknown ids surface as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput]. The genre
     * taxonomy is curator-controlled — books can only join existing genres.
     *
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. Server-side guard limits inputs to 200; overflow surfaces as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput].
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full chapter list for the book identified by [id] with
     * [chapters], and marks the book's chapter provenance as
     * [com.calypsan.listenup.api.sync.ChapterSource.USER] so a later rescan
     * will not overwrite the edit.
     *
     * Chapters are contiguous and absolute-time: [com.calypsan.listenup.api.dto.ChapterInput.startTime]
     * is the offset from the start of the book. The server validates the set
     * (strictly increasing starts, all within the book duration); violations
     * surface as [com.calypsan.listenup.api.error.BookError.InvalidInput].
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. On success the substrate emits a sync `Updated<BookSyncPayload>`;
     * clients update Room reactively.
     */
    suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit>

    /**
     * Removes the cover from the book identified by [id]: nulls cover state on
     * the book row and best-effort-deletes the underlying file after commit.
     *
     * Returns [com.calypsan.listenup.api.error.CoverError.NotPresent] when the
     * book has no cover to delete. Returns
     * [com.calypsan.listenup.api.error.BookError.NotFound] when no book exists.
     */
    suspend fun deleteBookCover(id: BookId): AppResult<Unit>
}
