package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import kotlinx.rpc.annotations.Rpc

/**
 * Cache-miss reads for OUR series entities. Pairs with the syncable series
 * domain (Books-B1) — clients observe Room locally; when a series is
 * referenced but not yet synced, they call this service for the single-entity
 * fallback.
 *
 * External metadata lookups live on [MetadataLookupService] — separate service
 * because local reads (fast, no external calls) and external lookups (slow,
 * rate-limited, fallible) have different transport semantics.
 */
@Rpc
interface SeriesService {
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
}
