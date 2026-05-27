package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Pure RPC dispatcher for book edits.
 *
 * No optimistic Room writes — the SSE echo from the server is the single write
 * path back into Room. This keeps state consistent across devices and matches
 * the Tags / playback-positions write pattern.
 *
 * Wire [WireAppResult] values returned by the RPC service are converted to the
 * client-layer [AppResult] at this boundary, following the same pattern as
 * [TagRepositoryImpl].
 */
class BookEditRepositoryImpl(
    private val bookRpcFactory: BookRpcFactory,
) : BookEditRepository {
    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().updateBook(id, patch) }

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookContributors(id, contributors) }

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookSeries(id, series) }

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> =
        rpcCallUnit { bookRpcFactory.bookService().deleteBookCover(id) }

    /**
     * Run an RPC call that returns [Unit], converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(Unit)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Book edit RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
