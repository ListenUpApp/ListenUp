package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import kotlinx.rpc.annotations.Rpc

/**
 * Cache-miss reads for OUR contributor entities. Pairs with the syncable
 * contributor domain (Books-B1) — clients observe Room locally; when a
 * contributor is referenced but not yet synced, they call this service for the
 * single-entity fallback.
 *
 * External Audible/iTunes metadata lookups live on [MetadataLookupService] —
 * separate service because local reads (fast, no external calls) and external
 * lookups (slow, rate-limited, fallible) have different transport semantics.
 */
@Rpc
interface ContributorService {
    /**
     * Returns the contributor aggregate for [id], or `null` when no such
     * contributor exists on the server.
     *
     * The primary use case is a cache miss: a book aggregate references a
     * contributor the client's Room database hasn't synced yet. Rather than
     * showing an error, the client calls [getContributor] to fetch the entity
     * on demand and render immediately while sync catches up in the background.
     */
    suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?>

    /**
     * Returns all books associated with [id] in rank-stable order.
     *
     * Clients hydrate book detail from Room for ids already cached and call
     * `BookService.getBook` for any that are not. A contributor with no books
     * returns an empty list — never a failure.
     */
    suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>>
}
