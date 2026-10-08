package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId

/**
 * [MatchingRepository] over [RpcChannel]. Find (books and people) only reads, so it is idempotent — the channel may re-send it
 * after a reconnect — and offline it folds to a typed transport failure, as every RPC call does.
 */
internal class MatchingRepositoryImpl(
    private val channel: RpcChannel<MatchingService>,
) : MatchingRepository {
    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> = channel.call(idempotent = true) { it.findBookMatches(bookId, request) }

    override suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult> = channel.call(idempotent = true) { it.findPeople(contributorId, request) }

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview> = channel.call(idempotent = true) { it.reviewBookMatch(bookId, candidate, region) }

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<MatchReceipt> = channel.callMutation { it.applyBookMatch(bookId, request) }

    override suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
    ): AppResult<PersonMatchReview> =
        channel.call(idempotent = true) {
            it.reviewPersonMatch(contributorId, candidate, role = null)
        }

    override suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<MatchReceipt> = channel.callMutation { it.applyPersonMatch(contributorId, request) }

    override suspend fun undoMatch(receiptId: String): AppResult<UndoResult> =
        channel.callMutation { it.undoMatch(receiptId) }
}
