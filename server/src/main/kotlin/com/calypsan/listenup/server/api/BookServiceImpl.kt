package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.server.services.BookRepository

private const val MAX_SEARCH_LIMIT = 200

/**
 * Thin [BookService] implementation. The work lives in [BookRepository]; this
 * class translates between the wire contract and the repository's public API.
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
}
