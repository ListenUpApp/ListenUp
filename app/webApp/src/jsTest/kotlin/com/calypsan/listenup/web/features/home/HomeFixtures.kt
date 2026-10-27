package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.presentation.home.AlsoFinished
import com.calypsan.listenup.client.presentation.home.FinishedBook
import com.calypsan.listenup.client.presentation.home.FinishedCard
import com.calypsan.listenup.client.presentation.home.HeroChapter
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.core.ShelfId

private const val HOUR_MS = 3_600_000L

/** One hydrated Continue Listening slot, roughly half finished by default. */
internal fun continuing(
    id: String,
    title: String,
    progress: Float = 0.5f,
    totalHours: Long = 10,
    narrators: String? = null,
): ContinueListeningItem.Ready =
    ContinueListeningItem.Ready(
        bookId = id,
        book =
            ContinueListeningBook(
                bookId = id,
                title = title,
                authorNames = "A. Writer",
                coverPath = null,
                coverHash = null,
                progress = progress,
                currentPositionMs = (totalHours * HOUR_MS * progress).toLong(),
                totalDurationMs = totalHours * HOUR_MS,
                narratorNames = narrators,
                lastPlayedAtMs = 1_704_067_200_000L,
                isDownloaded = false,
            ),
    )

/**
 * The Resume kind for [id]: 11% through 45h 30m, read by two narrators, in chapter 6, with Words of Radiance next.
 */
internal fun resumeSlot(
    id: String,
    canPlay: Boolean = true,
    lastPlayedAtMs: Long = 1_759_990_000_000L,
): HomeSlot.Resume {
    val total = 163_800_000L
    return HomeSlot.Resume(
        book =
            ContinueListeningBook(
                bookId = id,
                title = "The Way of Kings",
                authorNames = "Brandon Sanderson",
                coverPath = null,
                coverHash = null,
                progress = 0.11f,
                currentPositionMs = (total * 0.11).toLong(),
                totalDurationMs = total,
                narratorNames = "Michael Kramer, Kate Reading",
                lastPlayedAtMs = lastPlayedAtMs,
                isDownloaded = false,
            ),
        next =
            NextInSeries(
                bookId = "wor",
                title = "Words of Radiance",
                coverPath = null,
                coverHash = null,
                narratorNames = "Michael Kramer, Kate Reading",
                seriesName = "The Stormlight Archive",
                sequenceLabel = "2",
                durationMs = 173_700_000L,
            ),
        chapter = HeroChapter(number = 6, title = "Bridge Four"),
        canPlay = canPlay,
    )
}

/** A Home state that is loaded and quiet — nothing syncing, nothing scanning. */
internal fun readyHome(
    myShelves: List<Shelf> = emptyList(),
    isSyncing: Boolean = false,
    isBuildingInitialLibrary: Boolean = false,
    isLibraryArriving: Boolean = isBuildingInitialLibrary,
    scanProgress: ScanProgressState? = null,
    slot: HomeSlot = HomeSlot.Absent,
    connectivity: HomeConnectivity = HomeConnectivity.Online,
    continueListeningError: AppError? = null,
    timeOfDay: TimeOfDay = TimeOfDay.Afternoon,
    firstName: String? = "Simon",
    everythingInProgress: List<ContinueListeningItem> = emptyList(),
): HomeUiState.Ready =
    HomeUiState.Ready(
        myShelves = myShelves,
        isSyncing = isSyncing,
        isBuildingInitialLibrary = isBuildingInitialLibrary,
        isLibraryArriving = isLibraryArriving,
        scanProgress = scanProgress,
        timeOfDay = timeOfDay,
        firstName = firstName,
        slot = slot,
        everythingInProgress = everythingInProgress,
        connectivity = connectivity,
        continueListeningError = continueListeningError,
    )

/** A shelf with enough shape for Home to render a card for it. */
internal fun shelf(
    name: String,
    books: Int = 12,
    seconds: Long = 0L,
    isPrivate: Boolean = false,
    covers: List<ShelfCover> = emptyList(),
): Shelf =
    Shelf(
        id = ShelfId("shelf-$name"),
        name = name,
        description = null,
        isPrivate = isPrivate,
        ownerId = "user-1",
        ownerDisplayName = "Simon",
        bookCount = books,
        totalDurationSeconds = seconds,
        createdAtMs = 0,
        updatedAtMs = 0,
        covers = covers,
    )

/**
 * Seven day buckets, today first — the order the repository emits, NOT the order they are drawn.
 * [todaySeconds] is deliberately the smallest value so a spec can tell which bar is today by its
 * height as well as by its class.
 */
internal fun weekStats(
    todaySeconds: Long = 60,
    peakSeconds: Long = 3_600,
    currentStreakDays: Int = 5,
    longestStreakDays: Int = 14,
    topGenres: List<GenreShare> = emptyList(),
): HomeStatsUiState.Data =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = todaySeconds + peakSeconds,
        currentStreakDays = currentStreakDays,
        longestStreakDays = longestStreakDays,
        dailyBuckets =
            listOf(DayBucket(0, todaySeconds), DayBucket(1, peakSeconds)) +
                (2..6).map { DayBucket(it, 0) },
        topGenres = topGenres,
    )

/** A live scan, mid-analysis. */
internal fun scanning(
    books: Int = 40,
    booksTotal: Int = 100,
): ScanProgressState =
    ScanProgressState(
        phase = "analyzing",
        current = books,
        total = booksTotal,
        added = 3,
        updated = 1,
        removed = 0,
        books = books,
        booksTotal = booksTotal,
    )

/** The board's "now": 2025-10-10 12:00 UTC. */
internal const val BOARD_NOW = 1_760_097_600_000L

/** One day, in milliseconds. */
internal const val DAY_MS = 24 * HOUR_MS

/** The next book in a series: "Words of Radiance", Stormlight Archive Book 2, 48h 15m. */
internal fun nextBook(
    id: String = "wor",
    sequenceLabel: String? = "2",
): NextInSeries =
    NextInSeries(
        bookId = id,
        title = "Words of Radiance",
        coverPath = null,
        coverHash = null,
        narratorNames = "Michael Kramer & Kate Reading",
        seriesName = "Stormlight Archive",
        sequenceLabel = sequenceLabel,
        durationMs = 173_700_000L,
    )

/** The just-finished kind: "The Way of Kings", 45h 30m, finished an hour before [BOARD_NOW]. */
internal fun finishedSlot(
    next: NextInSeries? = nextBook(),
    names: List<String> = listOf("Maya", "Tom"),
    more: Int = 0,
    startedAtMs: Long? = BOARD_NOW - 34 * DAY_MS,
): HomeSlot.JustFinished =
    HomeSlot.JustFinished(
        FinishedCard(
            book =
                FinishedBook(
                    bookId = "wok",
                    title = "The Way of Kings",
                    authorNames = "Brandon Sanderson",
                    coverPath = null,
                    coverHash = null,
                    durationMs = 163_800_000L,
                ),
            startedAtMs = startedAtMs,
            finishedAtMs = BOARD_NOW - HOUR_MS,
            yourHalfStars = null,
            next = next,
            alsoFinished = AlsoFinished(names = names, othersCount = more),
        ),
    )

/** Nothing in progress, with or without a series candidate. */
internal fun nothingSlot(candidate: NextInSeries? = nextBook()): HomeSlot.NothingInProgress =
    HomeSlot.NothingInProgress(candidate)

/** A new listener, with [covers] of the newest books. */
internal fun welcomeSlot(covers: Int = 4): HomeSlot.NewListener =
    HomeSlot.NewListener(covers = (1..covers).map { ShelfCover("new$it", coverPath = null, coverHash = null) })
