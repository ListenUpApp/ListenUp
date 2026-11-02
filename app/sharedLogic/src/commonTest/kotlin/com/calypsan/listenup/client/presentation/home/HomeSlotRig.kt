package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.test.fake.FakeActivityRepository
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.fake.FakeBookRatingRepository
import com.calypsan.listenup.client.test.fake.FakeHomeRepository
import com.calypsan.listenup.client.test.fake.FakeLoadedBookRepository
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal const val RIG_NOW = 1_800_000_000_000L
internal const val RIG_HOUR = 3_600_000L

/**
 * Everything [HomeSlotPresenter] reads, as in-memory state a test moves by hand: the loaded book, positions, held
 * books, series, chapters, ratings, fellow finishers and the library showcase. [now] is the clock.
 */
internal class HomeSlotRig(
    failWrites: Boolean = false,
) {
    var now = RIG_NOW
    val loaded = MutableStateFlow<LoadedBook>(LoadedBook.NothingLoaded)
    val positions = FakePlaybackPositionRepository(nowMs = { now })
    val inbox = FakeInboxRepository()
    val nextByBook = MutableStateFlow<Map<String, NextInSeries>>(emptyMap())
    val books = FakeBookRepository()
    val ratings = FakeBookRatingRepository()
    val ratingsFlow = ratings.ratings
    private val activities = FakeActivityRepository()
    val finishers = activities.finishers
    private val home = FakeHomeRepository()
    val showcase = home.showcase
    val continueListening = MutableStateFlow<List<ContinueListeningItem>>(emptyList())
    val building = MutableStateFlow(false)
    val runs = MutableStateFlow(0)
    val dismissedHere = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** How many times the presenter asked for a book's next-in-series flow. */
    var nextInSeriesCalls = 0
        private set

    /** What the next rating write answers; a test flips it to a failure. */
    var rateResult: AppResult<Unit>
        get() = ratings.rateResult
        set(value) {
            ratings.rateResult = value
        }

    private val writablePositions: PlaybackPositionRepository =
        if (failWrites) {
            object : PlaybackPositionRepository by positions {
                override suspend fun savePlaybackState(
                    bookId: BookId,
                    update: PlaybackUpdate,
                ): AppResult<Unit> = AppResult.Failure(UnexpectedClientError())
            }
        } else {
            positions
        }

    private val loadedBooks = FakeLoadedBookRepository(loaded = loaded)

    fun presenter(): HomeSlotPresenter =
        HomeSlotPresenter(
            loadedBooks = loadedBooks,
            positions = writablePositions,
            inbox = inbox,
            nextInSeries = { bookId ->
                nextInSeriesCalls++
                nextByBook.map { it[bookId] }
            },
            books = books,
            ratings = ratings,
            activities = activities,
            authSession = FakeAuthSession(userId = "me"),
            home = home,
            nowMs = { now },
        )

    fun slots(): Flow<HomeSlotState?> = presenter().observe(continueListening, building, runs, dismissedHere)

    fun play(
        bookId: String,
        hoursAgo: Long,
        positionMs: Long = 1_000L,
    ) = positions.setPosition(slotPosition(bookId, positionMs = positionMs, lastPlayedAtMs = now - hoursAgo * RIG_HOUR))

    fun finish(
        bookId: String,
        hoursAgo: Long,
    ) = positions.setPosition(
        slotPosition(
            bookId,
            positionMs = 9_000L,
            lastPlayedAtMs = now - hoursAgo * RIG_HOUR,
            isFinished = true,
            finishedAtMs = now - hoursAgo * RIG_HOUR,
        ),
    )
}

internal fun slotPosition(
    bookId: String,
    positionMs: Long = 1_000L,
    lastPlayedAtMs: Long? = RIG_NOW,
    isFinished: Boolean = false,
    finishedAtMs: Long? = null,
) = PlaybackPosition(
    bookId = bookId,
    positionMs = positionMs,
    playbackSpeed = 1.0f,
    hasCustomSpeed = false,
    volumeBoostDb = 0f,
    hasCustomBoost = false,
    measuredGainDb = null,
    updatedAtMs = lastPlayedAtMs ?: 0L,
    syncedAtMs = null,
    lastPlayedAtMs = lastPlayedAtMs,
    isFinished = isFinished,
    finishedAtMs = finishedAtMs,
    startedAtMs = finishedAtMs?.minus(10 * RIG_HOUR),
)

internal fun clBook(
    id: String,
    positionMs: Long = 1_000L,
) = ContinueListeningBook(
    bookId = id,
    title = "Book $id",
    authorNames = "A. Writer",
    narratorNames = null,
    coverPath = null,
    coverHash = null,
    progress = 0.1f,
    currentPositionMs = positionMs,
    totalDurationMs = 10_000L,
    lastPlayedAtMs = RIG_NOW,
    isDownloaded = false,
)

internal fun readyItem(
    id: String,
    positionMs: Long = 1_000L,
) = ContinueListeningItem.Ready(id, clBook(id, positionMs))

internal fun nextBook(id: String) =
    NextInSeries(
        bookId = id,
        title = "Next $id",
        coverPath = null,
        coverHash = null,
        narratorNames = "Kate Reading",
        seriesName = "Saga",
        sequenceLabel = "2",
        durationMs = 1_000L,
    )
