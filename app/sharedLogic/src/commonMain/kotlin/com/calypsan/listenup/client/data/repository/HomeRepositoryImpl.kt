@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.domain.BookProgress
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LibraryShowcase
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// How many newest books to look through per cover wanted: art is common, not universal (plan call C15).
private const val SHOWCASE_OVERSCAN = 4

/**
 * Repository for Home screen data.
 *
 * Handles fetching continue listening books.
 *
 * Architecture: Local-first approach.
 * - Always use local data as primary source (most up-to-date for this device)
 * - Local positions are updated immediately during playback
 * - Server sync happens in background and updates local DB
 *
 * @property bookRepository Repository for fetching book details
 * @property playbackPositionDao DAO for playback positions
 * @property downloadRepository whether each card's book is on this device
 */
internal class HomeRepositoryImpl(
    private val bookRepository: BookRepository,
    private val playbackPositionDao: PlaybackPositionDao,
    private val downloadRepository: DownloadRepository,
) : HomeRepository {
    private val logger = KotlinLogging.logger {}

    /**
     * The books to resume, most recent first: [continueListeningItems] — the shelf's own list — with only the hydrated
     * books kept, then [limit] taken. Android Auto, session and navigation resume, shortcuts, voice and Siri all read
     * this, so they offer exactly what Home shows, in the same order.
     *
     * @param limit Maximum number of books to return
     * @return Result containing list of ContinueListeningBook on success
     */
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> =
        suspendRunCatching {
            continueListeningItems()
                .first()
                .filterIsInstance<ContinueListeningItem.Ready>()
                .map { it.book }
                .take(limit)
        }

    /**
     * Observe continue listening items from local database: [continueListeningItems], then [limit] taken (a
     * [ContinueListeningItem.Loading] placeholder counts, so the shelf's size stays stable while a book syncs).
     *
     * @param limit Maximum number of items to return
     * @return Flow emitting list of [ContinueListeningItem] whenever positions or books change
     */
    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> =
        continueListeningItems().map { items ->
            items.take(limit).also { logger.info { "observeContinueListening: returning ${it.size} items" } }
        }

    override fun observeLibraryShowcase(coverLimit: Int): Flow<LibraryShowcase> =
        bookRepository.observeRecentlyAddedBooks(limit = coverLimit * SHOWCASE_OVERSCAN).map { newest ->
            LibraryShowcase(
                covers =
                    newest
                        .filter { it.coverHash != null }
                        .take(coverLimit)
                        .map { ShelfCover(bookId = it.id, coverPath = it.coverPath, coverHash = it.coverHash) },
            )
        }

    /**
     * The one Continue Listening list, unlimited, re-emitting whenever a position, a book or a download changes. The
     * candidates come from [PlaybackPositionDao.observeContinueListeningCandidates] in its order; the book side is a
     * live join ([BookRepository.observeBookListItems]), so a book that syncs in after its position re-emits rather
     * than being captured stale.
     */
    private fun continueListeningItems(): Flow<List<ContinueListeningItem>> =
        playbackPositionDao
            .observeContinueListeningCandidates()
            .flatMapLatest { positions ->
                if (positions.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        bookRepository.observeBookListItems(positions.map { it.bookId.value }),
                        downloadRepository.observeAllStatuses(),
                    ) { books, downloads -> continueListeningItemsOf(positions, books, downloads) }
                }
            }

    /**
     * Each candidate position, in order, as a shelf item: [ContinueListeningItem.Loading] while its book has not yet
     * synced into Room (dropping it made the shelf look half-empty), left out unless [BookProgress] says it is in
     * progress (finished by the flag or the end credits, or not started), [ContinueListeningItem.Ready] otherwise.
     */
    private fun continueListeningItemsOf(
        positions: List<PlaybackPositionEntity>,
        books: List<BookListItem>,
        downloads: Map<String, BookDownloadStatus>,
    ): List<ContinueListeningItem> {
        val bookMap = books.associateBy { it.id.value }
        return positions.mapNotNull { position ->
            val book =
                bookMap[position.bookId.value] ?: return@mapNotNull ContinueListeningItem.Loading(position.bookId.value)
            val progress = BookProgress.of(position.positionMs, book.duration, position.isFinished)
            if (progress !is BookProgress.InProgress) return@mapNotNull null
            ContinueListeningItem.Ready(
                bookId = position.bookId.value,
                book = toContinueListeningBook(position, book, downloads),
            )
        }
    }

    private fun toContinueListeningBook(
        position: PlaybackPositionEntity,
        book: BookListItem,
        downloads: Map<String, BookDownloadStatus>,
    ): ContinueListeningBook =
        ContinueListeningBook(
            bookId = position.bookId.value,
            title = book.title,
            authorNames = book.authorNames,
            narratorNames = book.narrators.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            currentPositionMs = position.positionMs,
            totalDurationMs = book.duration,
            lastPlayedAtMs = position.lastPlayedAt ?: position.updatedAt,
            isDownloaded = downloads[position.bookId.value] is BookDownloadStatus.Completed,
        )
}
