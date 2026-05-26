package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.services.BookRepository

private const val MAX_SEARCH_LIMIT = 200

/**
 * Thin [BookService] implementation. The work lives in [BookRepository]; this
 * class translates between the wire contract and the repository's public API.
 *
 * Mutation methods ([updateBook], [setBookContributors], [setBookSeries],
 * [deleteBookCover]) are stub implementations returning [BookError.NotFound]
 * until Tasks 13–16 replace them with real logic.
 */
internal class BookServiceImpl(
    private val repo: BookRepository,
) : BookService {
    override suspend fun getBook(id: BookId): AppResult<BookSyncPayload> {
        val payload = repo.findById(id)
        return if (payload != null) {
            AppResult.Success(payload)
        } else {
            AppResult.Failure(SyncError.NotFound(domain = "book", entityId = id.value))
        }
    }

    override suspend fun searchBooks(
        query: String,
        limit: Int,
    ): AppResult<List<BookId>> {
        if (query.isBlank()) return AppResult.Success(emptyList())
        return AppResult.Success(repo.searchFts(query, limit.coerceIn(1, MAX_SEARCH_LIMIT)))
    }

    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> =
        AppResult.Failure(BookError.NotFound(debugInfo = "updateBook not yet implemented (Books-C1 Task 13)"))

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> =
        AppResult.Failure(BookError.NotFound(debugInfo = "setBookContributors not yet implemented (Books-C1 Task 14)"))

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> =
        AppResult.Failure(BookError.NotFound(debugInfo = "setBookSeries not yet implemented (Books-C1 Task 15)"))

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> =
        AppResult.Failure(BookError.NotFound(debugInfo = "deleteBookCover not yet implemented (Books-C1 Task 16)"))
}
