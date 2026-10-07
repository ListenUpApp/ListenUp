@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId

/**
 * Match details over [com.calypsan.listenup.api.MatchingService] — one method per RPC, `:contract` DTOs
 * exposed directly. Internal until a ViewModel brings Match details to the platforms, so the Swift surface is
 * untouched until then.
 */
internal interface MatchingRepository {
    /** Finds [bookId] in every catalogue and ranks the candidates against it. Retry is this same call. */
    suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest = BookFindRequest(),
    ): AppResult<BookFindResult>

    /**
     * Finds [contributorId] in every catalogue with profiles for the request's role, ranked by the books they're
     * credited on here. Retry is this same call.
     */
    suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult>

    /** Reviews [candidate] against [bookId], field by field. Only reads, so a retry is safe. */
    suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale? = null,
    ): AppResult<BookMatchReview>

    /**
     * Applies [request] in one server transaction. The book's change is applied to Room before this returns
     * (read-your-writes); the value is the receipt.
     */
    suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<MatchReceipt>

    /** Undoes the match [receiptId]; the restored book is applied to Room before this returns. */
    suspend fun undoMatch(receiptId: String): AppResult<UndoResult>
}
