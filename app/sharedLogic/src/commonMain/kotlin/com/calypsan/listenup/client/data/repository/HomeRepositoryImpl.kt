@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.domain.FinishedPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LibraryShowcase
import com.calypsan.listenup.client.domain.model.ShelfCover
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
    private val bookRepository: com.calypsan.listenup.client.domain.repository.BookRepository,
    private val playbackPositionDao: PlaybackPositionDao,
    private val downloadRepository: DownloadRepository,
) : HomeRepository {
    private val logger = KotlinLogging.logger {}

    /**
     * Fetch books the user is currently listening to.
     *
     * Local-first approach:
     * - Always use local data as primary source (most up-to-date for this device)
     * - Local positions are updated immediately during playback
     * - Server sync happens in background and updates local DB
     *
     * This ensures instant updates after playback without waiting for sync.
     *
     * @param limit Maximum number of books to return
     * @return Result containing list of ContinueListeningBook on success
     */
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> {
        logger.debug { "getContinueListening: using local-first approach" }
        return fetchFromLocal(limit)
    }

    /**
     * Fallback: fetch from local database when offline.
     * Requires client-side join with book details.
     */
    private suspend fun fetchFromLocal(limit: Int): AppResult<List<ContinueListeningBook>> {
        val positions = playbackPositionDao.getRecentPositions(limit)
        logger.info { "fetchFromLocal: found ${positions.size} playback positions" }

        if (positions.isEmpty()) {
            return AppResult.Success(emptyList())
        }

        var booksNotFound = 0
        var booksFiltered = 0

        val bookIds = positions.map { it.bookId.value }
        val bookMap = bookRepository.getBookListItems(bookIds).associateBy { it.id.value }
        val downloads = downloadRepository.observeAllStatuses().first()

        val books =
            positions.mapNotNull { position ->
                val bookIdStr = position.bookId.value
                val book =
                    bookMap[bookIdStr] ?: run {
                        booksNotFound++
                        logger.warn { "Local fallback: book not found - id=$bookIdStr" }
                        return@mapNotNull null
                    }

                val effectivelyFinished =
                    FinishedPolicy.isFinished(position.positionMs, book.duration, position.isFinished)
                if (effectivelyFinished) {
                    booksFiltered++
                    logger.debug {
                        "Local fallback: skipping finished book - id=$bookIdStr, " +
                            "position=${position.positionMs}, duration=${book.duration}"
                    }
                    return@mapNotNull null
                }

                toContinueListeningBook(position, book, downloads)
            }

        logger.info {
            "fetchFromLocal: returning ${books.size} books " +
                "(positions=${positions.size}, notFound=$booksNotFound, filtered=$booksFiltered)"
        }
        return AppResult.Success(books)
    }

    /**
     * Observe continue listening items from local database.
     *
     * Uses [BookRepository.observeBookListItems] for a fully reactive join: when positions
     * change, the book side re-subscribes; when books change (sync in-flight), the book Flow
     * re-emits — eliminating the race where stale book data was captured once per position
     * emission.
     *
     * Items are [ContinueListeningItem.Ready] when the book is hydrated, or
     * [ContinueListeningItem.Loading] when the book has not yet arrived in Room (brief
     * sync-window — silently dropping those rows made the shelf appear half-empty).
     *
     * @param limit Maximum number of items to return
     * @return Flow emitting list of [ContinueListeningItem] whenever positions or books change
     */
    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> =
        // Push the sort + limit + isFinished=0 filter to SQL. Room still
        // re-emits on any row change, so the Home shelf stays reactive without
        // pulling every position to the client.
        playbackPositionDao
            .observeRecentPositions(limit)
            .flatMapLatest { positions ->
                if (positions.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val bookIds = positions.map { it.bookId.value }
                    combine(
                        bookRepository.observeBookListItems(bookIds),
                        downloadRepository.observeAllStatuses(),
                    ) { books, downloads ->
                        buildContinueListeningItems(
                            positions = positions,
                            books = books,
                            downloads = downloads,
                            limit = limit,
                        )
                    }
                }
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

    private fun buildContinueListeningItems(
        positions: List<PlaybackPositionEntity>,
        books: List<BookListItem>,
        downloads: Map<String, BookDownloadStatus>,
        limit: Int,
    ): List<ContinueListeningItem> {
        val bookMap = books.associateBy { it.id.value }
        val result =
            positions
                .asSequence()
                .mapNotNull { pos ->
                    val book = bookMap[pos.bookId.value]
                    if (book == null) {
                        // D: sync in-flight — show Loading placeholder so shelf size stays stable
                        ContinueListeningItem.Loading(pos.bookId.value)
                    } else {
                        // A: the flag, or a position in the end credits — one rule, FinishedPolicy
                        val effectivelyFinished =
                            FinishedPolicy.isFinished(pos.positionMs, book.duration, pos.isFinished)
                        if (effectivelyFinished) {
                            null // exclude
                        } else {
                            ContinueListeningItem.Ready(
                                bookId = pos.bookId.value,
                                book = toContinueListeningBook(pos, book, downloads),
                            )
                        }
                    }
                }.take(limit)
                .toList()

        logger.info { "observeContinueListening: returning ${result.size} items" }
        return result
    }

    private fun toContinueListeningBook(
        position: PlaybackPositionEntity,
        book: BookListItem,
        downloads: Map<String, BookDownloadStatus>,
    ): ContinueListeningBook {
        val progress =
            if (book.duration > 0) {
                (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        return ContinueListeningBook(
            bookId = position.bookId.value,
            title = book.title,
            authorNames = book.authorNames,
            narratorNames = book.narrators.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            progress = progress,
            currentPositionMs = position.positionMs,
            totalDurationMs = book.duration,
            lastPlayedAtMs = position.lastPlayedAt ?: position.updatedAt,
            isDownloaded = downloads[position.bookId.value] is BookDownloadStatus.Completed,
        )
    }
}
