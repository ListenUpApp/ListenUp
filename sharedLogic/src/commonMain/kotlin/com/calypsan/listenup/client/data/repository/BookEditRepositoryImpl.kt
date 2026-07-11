package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
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
 * still arrives via the SSE sync engine and reconciles through the books
 * domain's composed handler ([com.calypsan.listenup.client.data.sync.domains.booksDomain]).
 *
 * The remaining methods stay pure RPC dispatchers — the SSE echo from the server
 * is their single write path back into Room. Wire [WireAppResult] values returned
 * by the RPC service are converted to the client-layer [AppResult] at this
 * boundary, following the same pattern as [TagRepositoryImpl].
 */
internal class BookEditRepositoryImpl(
    private val bookRpcFactory: BookRpcFactory,
    private val collectionChannel: RpcChannel<CollectionService>,
    private val bookDao: BookDao,
    private val offlineEditor: OfflineEditor,
) : BookEditRepository {
    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Books, id.value, patch) {
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
                        // Mirror the server's per-field provenance union locally so the rescan-protected set
                        // is correct offline and before the SSE echo — matches BookServiceImpl.applyPatch.
                        userEditedFields = existing.userEditedFields + patch.editedFields(),
                        // revision + updatedAt deliberately untouched; addedAt handled server-side.
                    ),
                )
            }
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
        collectionChannel.call { it.setBookCollections(id, collectionIds.map { c -> CollectionId(c) }) }

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

/**
 * The rescan-protected fields this patch edits — a non-null scalar means the user set it.
 *
 * Mirrors `BookServiceImpl.applyPatch` exactly so the client's optimistic provenance matches what the
 * server will record. Only [UserEditedField] scalars are mapped here; contributor and series
 * provenance is set by their own RPCs ([UserEditedField.CONTRIBUTORS] / [UserEditedField.SERIES]).
 */
private fun BookUpdate.editedFields(): Set<UserEditedField> =
    buildSet {
        if (title != null) add(UserEditedField.TITLE)
        if (subtitle != null) add(UserEditedField.SUBTITLE)
        if (description != null) add(UserEditedField.DESCRIPTION)
    }
