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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val MAX_SEARCH_LIMIT = 200

/**
 * Thin [BookService] implementation. The work lives in [BookRepository]; this
 * class translates between the wire contract and the repository's public API.
 *
 * [updateBook] reads the current aggregate, applies the [BookUpdate] patch
 * field-by-field (null means "don't touch"), and writes the patched payload
 * through the syncable substrate so the revision bumps and the change-bus
 * fires uniformly. The read-then-write straddles two repository calls; both
 * run inside the same [suspendTransaction] so the substrate's own transaction
 * nests cleanly. The remaining mutation methods ([setBookContributors],
 * [setBookSeries], [deleteBookCover]) are stub implementations returning
 * [BookError.NotFound] until Tasks 14–16 replace them with real logic.
 */
internal class BookServiceImpl(
    private val repo: BookRepository,
    private val db: Database,
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
        suspendTransaction(db) {
            val current =
                repo.findById(id)
                    ?: return@suspendTransaction AppResult.Failure(
                        BookError.NotFound(debugInfo = "bookId=${id.value}"),
                    )
            when (val upsertResult = repo.upsert(current.applyPatch(patch))) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(upsertResult.error)
            }
        }

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

private fun BookSyncPayload.applyPatch(patch: BookUpdate): BookSyncPayload =
    copy(
        title = patch.title ?: title,
        sortTitle = patch.sortTitle ?: sortTitle,
        subtitle = patch.subtitle ?: subtitle,
        description = patch.description ?: description,
        publisher = patch.publisher ?: publisher,
        publishYear = patch.publishYear ?: publishYear,
        language = patch.language ?: language,
        isbn = patch.isbn ?: isbn,
        asin = patch.asin ?: asin,
        abridged = patch.abridged ?: abridged,
    )
