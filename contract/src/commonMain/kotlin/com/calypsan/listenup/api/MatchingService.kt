package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import kotlinx.rpc.annotations.Rpc

/**
 * Match details: find a book or a person in every catalogue, review per field, apply once, undo.
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

    /**
     * Finds [contributorId] in every catalogue with profiles for [PersonFindRequest.role] — authors at Audible and
     * Hardcover, narrators at Hardcover — starting from their own refs, then the books they're credited on in this
     * library, then their name, and ranks what it finds by those books. Gated like [findBookMatches]: the caller
     * needs `canEdit`; an unknown contributor is `MetadataError.NotFound`. Every source failing, or none having
     * profiles for the role, is still a success: [PersonFindResult.sources] and [PersonFindResult.coverage] say why.
     */
    suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult>
}
