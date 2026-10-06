package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.BookId

/**
 * [MatchingRepository] over [RpcChannel]. Find only reads, so it is idempotent — the channel may re-send it
 * after a reconnect — and offline it folds to a typed transport failure, as every RPC call does.
 */
internal class MatchingRepositoryImpl(
    private val channel: RpcChannel<MatchingService>,
) : MatchingRepository {
    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> = channel.call(idempotent = true) { it.findBookMatches(bookId, request) }
}
