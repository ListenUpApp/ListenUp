package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for series access and user-edit mutations.
 *
 * Two surface categories:
 * - **Observation** — [getSeries], [listBooksBySeries] are safe to call
 *   repeatedly. Cache-miss reads for OUR series entities — clients observe
 *   Room locally; when a series is referenced but not yet synced, they call
 *   this service for the single-entity fallback.
 * - **Mutation** — [updateSeries], [deleteSeries] mutate server state; the sync firehose
 *   delivers the authoritative payload back to all connected clients.
 *
 * External metadata lookups live on [MetadataLookupService] — separate service
 * because local reads (fast, no external calls) and external lookups (slow,
 * rate-limited, fallible) have different transport semantics.
 *
 * // TODO: gate mutations by user permissions when Multi-user lands
 */
@Rpc
interface SeriesService {
    // ── Observation (existing) ───────────────────────────────────────────────

    /**
     * Returns the series aggregate for [id], or `null` when no such series
     * exists on the server.
     *
     * The primary use case is a cache miss: a book aggregate references a
     * series the client's Room database hasn't synced yet. Rather than showing
     * an error, the client calls [getSeries] to fetch the entity on demand and
     * render immediately while sync catches up in the background.
     */
    suspend fun getSeries(id: SeriesId): AppResult<SeriesSyncPayload?>

    /**
     * Returns all books belonging to [id] in series-position order.
     *
     * Clients hydrate book detail from Room for ids already cached and call
     * `BookService.getBook` for any that are not. A series with no books
     * returns an empty list — never a failure.
     */
    suspend fun listBooksBySeries(id: SeriesId): AppResult<List<BookSyncPayload>>

    // ── Mutation (new in Books-C1) ───────────────────────────────────────────

    /**
     * PATCH the series identified by [id] with [patch]. Returns
     * [com.calypsan.listenup.api.error.SeriesError.NotFound] when no such
     * series exists.
     *
     * On a name or sortName change, `BookSearchReindexer.reindexAllBooksForSeries`
     * runs after the commit so `book_search.series_names` reflects the new name.
     * Reindex failure is logged WARN and swallowed; the RPC returns Success regardless.
     */
    suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_series_memberships` junction rows referencing [id],
     * re-upserts each affected book, then soft-deletes the series row. Books that
     * lose their series association stay as books.
     */
    suspend fun deleteSeries(id: SeriesId): AppResult<Unit>

    /**
     * Merges series [source] into series [target]. After this call:
     * - All `book_series_memberships` rows referencing [source] are re-linked to [target].
     * - All affected books are re-upserted with the new series reference.
     * - [source] is soft-deleted.
     *
     * Returns [com.calypsan.listenup.api.error.SeriesError.MergeSelfTarget] when
     * `source == target`. Returns [com.calypsan.listenup.api.error.SeriesError.NotFound]
     * when either is missing.
     *
     * Unlike contributor merge, series merge has no `credited_as` equivalent — the
     * canonical name change is the intended semantic ("these were always the same series").
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun mergeSeries(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit>
}
