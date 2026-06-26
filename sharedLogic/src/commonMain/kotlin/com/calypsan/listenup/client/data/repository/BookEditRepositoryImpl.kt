package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Offline-first book editor.
 *
 * [updateBook] writes the patch into Room immediately and enqueues a durable
 * pending op (the same outbox the playback-position writes use), so an edit made
 * offline persists and replays on reconnect rather than failing with a
 * [com.calypsan.listenup.api.error.ServerConnectError]. The authoritative state
 * still arrives via the SSE sync engine and reconciles through
 * [com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler].
 *
 * The remaining methods stay pure RPC dispatchers — the SSE echo from the server
 * is their single write path back into Room. Wire [WireAppResult] values returned
 * by the RPC service are converted to the client-layer [AppResult] at this
 * boundary, following the same pattern as [TagRepositoryImpl].
 */
internal class BookEditRepositoryImpl(
    private val bookRpcFactory: BookRpcFactory,
    private val collectionRpcFactory: CollectionRpcFactory,
    private val bookDao: BookDao,
    private val pendingQueue: PendingOperationQueue,
    private val transactionRunner: TransactionRunner,
    private val authSession: AuthSession,
) : BookEditRepository {
    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> {
        val ownerUserId =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        transactionRunner.atomically {
            bookDao.getById(id)?.let { existing ->
                bookDao.upsert(
                    existing.copy(
                        title = patch.title ?: existing.title,
                        sortTitle = patch.sortTitle ?: existing.sortTitle,
                        subtitle = patch.subtitle ?: existing.subtitle,
                        description = patch.description ?: existing.description,
                        publisher = patch.publisher ?: existing.publisher,
                        publishYear = patch.publishYear ?: existing.publishYear,
                        language = patch.language ?: existing.language,
                        isbn = patch.isbn ?: existing.isbn,
                        asin = patch.asin ?: existing.asin,
                        abridged = patch.abridged ?: existing.abridged,
                        // revision + updatedAt deliberately untouched; addedAt handled server-side.
                    ),
                )
            }
        }
        pendingQueue.enqueue(
            domainName = "books",
            entityId = id.value,
            opType = "update",
            payload = contractJson.encodeToString(BookUpdate.serializer(), patch),
            ownerUserId = ownerUserId,
        )
        return AppResult.Success(Unit)
    }

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookContributors(id, contributors) }

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookSeries(id, series) }

    override suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookGenres(id, genres) }

    override suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit> = rpcCallUnit { bookRpcFactory.bookService().setBookChapters(id, chapters) }

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> =
        rpcCallUnit { bookRpcFactory.bookService().deleteBookCover(id) }

    override suspend fun setBookCollections(
        id: BookId,
        collectionIds: List<String>,
    ): AppResult<Unit> =
        rpcCallUnit {
            collectionRpcFactory.get().setBookCollections(id, collectionIds.map { CollectionId(it) })
        }

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
