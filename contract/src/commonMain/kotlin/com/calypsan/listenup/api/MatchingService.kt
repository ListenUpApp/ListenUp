package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * Match details: find a book in every catalogue, review per field, apply once, undo.
 *
 * Supersedes [MetadataLookupService] for new clients; that service stays, unchanged, for older ones. Find,
 * Review and Apply are stateless on the server — a candidate is identified by its
 * [com.calypsan.listenup.api.dto.match.BookCandidateKey].
 */
@Rpc
interface MatchingService {
    /**
     * Finds [bookId] in every capable catalogue and ranks what it finds against the book itself. The caller
     * must be able to edit metadata and see the book; otherwise this is `MetadataError.NotFound`.
     *
     * Every source failing is still a success: [BookFindResult.sources] says how each fared, and the client
     * picks the failure screen. Retry is this same call — sources that already answered come back from the
     * server's cache.
     */
    suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult>
}
