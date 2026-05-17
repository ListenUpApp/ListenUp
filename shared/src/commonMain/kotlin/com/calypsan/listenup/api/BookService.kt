package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for on-demand book access and server-side FTS search.
 *
 * Two complementary operations:
 * - [getBook] is the never-stranded fallback for cache misses — when a search
 *   result or deep link points to a book that hasn't yet landed in the client's
 *   Room cache, [getBook] fetches the full aggregate directly from the server.
 * - [searchBooks] drives server-side FTS5 search, returning matching [BookId]s
 *   in rank order so clients can hydrate detail from Room or call [getBook] for
 *   any cache miss.
 */
@Rpc
interface BookService {
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
}
