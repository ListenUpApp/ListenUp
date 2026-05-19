@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
 */
class HomeRepositoryImpl(
    private val bookRepository: com.calypsan.listenup.client.domain.repository.BookRepository,
    private val playbackPositionDao: PlaybackPositionDao,
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
        positions.forEachIndexed { index, pos ->
            logger.debug {
                "  position[$index]: bookId=${pos.bookId.value}, positionMs=${pos.positionMs}, lastPlayedAt=${pos.lastPlayedAt}"
            }
        }

        if (positions.isEmpty()) {
            return Success(emptyList())
        }

        var booksNotFound = 0
        var booksFiltered = 0

        val bookIds = positions.map { it.bookId.value }
        val bookMap = bookRepository.getBookListItems(bookIds).associateBy { it.id.value }

        val books =
            positions.mapNotNull { position ->
                val bookIdStr = position.bookId.value
                val book =
                    bookMap[bookIdStr] ?: run {
                        booksNotFound++
                        logger.warn { "Local fallback: book not found - id=$bookIdStr" }
                        return@mapNotNull null
                    }

                val progress =
                    if (book.duration > 0) {
                        (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                // Derive finished state from position vs duration (#204, #208)
                // A book is finished if position >= duration OR (flag set AND near-complete)
                val effectivelyFinished =
                    book.duration > 0 && (
                        position.positionMs >= book.duration ||
                            (
                                position.isFinished &&
                                    position.positionMs.toFloat() / book.duration >= 0.95f
                            )
                    )
                if (effectivelyFinished) {
                    booksFiltered++
                    logger.debug {
                        "Local fallback: skipping finished book - id=$bookIdStr, " +
                            "position=${position.positionMs}, duration=${book.duration}"
                    }
                    return@mapNotNull null
                }

                // Use lastPlayedAt if available, fall back to updatedAt for legacy data
                val lastPlayedAtMs = position.lastPlayedAt ?: position.updatedAt
                val lastPlayedAtIso = Instant.fromEpochMilliseconds(lastPlayedAtMs).toString()

                ContinueListeningBook(
                    bookId = bookIdStr,
                    title = book.title,
                    authorNames = book.authorNames,
                    coverPath = book.coverPath,
                    coverBlurHash = book.coverBlurHash,
                    progress = progress,
                    currentPositionMs = position.positionMs,
                    totalDurationMs = book.duration,
                    lastPlayedAt = lastPlayedAtIso,
                )
            }

        logger.info {
            "fetchFromLocal: returning ${books.size} books " +
                "(positions=${positions.size}, notFound=$booksNotFound, filtered=$booksFiltered)"
        }
        return Success(books)
    }

    /**
     * Observe continue listening books from local database.
     *
     * Transforms playback positions into ContinueListeningBook objects
     * by joining with book details. Provides real-time updates when
     * positions change locally (instant, no sync delay).
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of ContinueListeningBook whenever positions change
     */
    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningBook>> =
        // Push the sort + limit + "started" filter to SQL (Finding 09). Room still
        // re-emits on any row change, so the Home shelf stays reactive without
        // pulling every position to the client. The finished-check stays in
        // Kotlin because it requires each book's duration, which Room doesn't
        // have in this query's projection.
        playbackPositionDao
            .observeRecentPositions(limit)
            .onEach { positions ->
                val finishedCount = positions.count { it.isFinished }
                logger.info {
                    "Room EMITTED: ${positions.size} positions (finished=$finishedCount)"
                }
            }.mapLatest { positions ->
                val result = mutableListOf<ContinueListeningBook>()
                val bookMap =
                    bookRepository
                        .getBookListItems(
                            positions.map { it.bookId.value },
                        ).associateBy { it.id.value }
                for (position in positions) {
                    val bookIdStr = position.bookId.value
                    val book = bookMap[bookIdStr] ?: continue

                    val effectivelyFinished =
                        book.duration > 0 && (
                            position.positionMs >= book.duration ||
                                (
                                    position.isFinished &&
                                        position.positionMs.toFloat() / book.duration >= 0.95f
                                )
                        )
                    if (effectivelyFinished) continue

                    val progress =
                        if (book.duration > 0) {
                            (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                    val lastPlayedAtMs = position.lastPlayedAt ?: position.updatedAt
                    val lastPlayedAtIso = Instant.fromEpochMilliseconds(lastPlayedAtMs).toString()

                    result.add(
                        ContinueListeningBook(
                            bookId = bookIdStr,
                            title = book.title,
                            authorNames = book.authorNames,
                            coverPath = book.coverPath,
                            coverBlurHash = book.coverBlurHash,
                            progress = progress,
                            currentPositionMs = position.positionMs,
                            totalDurationMs = book.duration,
                            lastPlayedAt = lastPlayedAtIso,
                        ),
                    )
                }
                logger.info { "observeContinueListening: returning ${result.size} books" }
                result
            }
}
