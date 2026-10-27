package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.home.AlsoFinished
import com.calypsan.listenup.client.presentation.home.FinishedBook
import com.calypsan.listenup.client.presentation.home.FinishedCard
import com.calypsan.listenup.client.presentation.home.HeroChapter
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.core.ShelfId

// Covers carry a local path so the host tests never reach Koin for a server URL (BookCoverImage's slow path).

/** A Home in its Ready state, every constructor argument named. */
internal fun homeReady(
    slot: HomeSlot,
    everythingInProgress: List<ContinueListeningItem>,
    shelves: List<Shelf> = emptyList(),
    connectivity: HomeConnectivity = HomeConnectivity.Online,
    isLibraryArriving: Boolean = false,
    continueListeningError: AppError? = null,
    scanProgress: ScanProgressState? = null,
) = HomeUiState.Ready(
    myShelves = shelves,
    isSyncing = false,
    isBuildingInitialLibrary = false,
    isLibraryArriving = isLibraryArriving,
    scanProgress = scanProgress,
    timeOfDay = TimeOfDay.Evening,
    firstName = "Simon",
    slot = slot,
    everythingInProgress = everythingInProgress,
    connectivity = connectivity,
    continueListeningError = continueListeningError,
)

private fun book(
    id: String,
    title: String,
    lastPlayedAtMs: Long = 0L,
) = ContinueListeningBook(
    bookId = id,
    title = title,
    authorNames = "A. Writer",
    narratorNames = "N. Reader",
    coverPath = "/tmp/cover-$id.webp",
    progress = 0.11f,
    currentPositionMs = 8_000_000L,
    totalDurationMs = 75_200_000L,
    lastPlayedAtMs = lastPlayedAtMs,
    isDownloaded = false,
)

/** A ready Also in progress card: author, narrators, 11% through. */
internal fun readyItem(
    id: String,
    title: String = "Book $id",
) = ContinueListeningItem.Ready(bookId = id, book = book(id, title))

/** The Resume kind for [id], with a chapter and a next book. */
internal fun resumeSlot(id: String) =
    HomeSlot.Resume(
        book = book(id, "Resume $id"),
        next =
            NextInSeries(
                bookId = "$id-next",
                title = "Next after $id",
                coverPath = "/tmp/cover-$id-next.webp",
                coverHash = null,
                narratorNames = null,
                seriesName = "Series",
                sequenceLabel = "2",
                durationMs = 3_600_000L,
            ),
        chapter = HeroChapter(number = 6, title = "Bridge Four"),
        canPlay = true,
    )

/** A week with a live 5-day streak, a 12-day best and three genres. */
internal fun weekWithStreak() =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = 29_520L,
        currentStreakDays = 5,
        longestStreakDays = 12,
        dailyBuckets =
            listOf(4_800L, 5_520L, 0L, 1_500L, 6_000L, 7_500L, 4_200L).mapIndexed {
                i,
                s,
                ->
                DayBucket(i, s)
            },
        topGenres =
            listOf(
                GenreShare("Fantasy", 6_000L),
                GenreShare("History", 4_000L),
                GenreShare("Mystery", 2_000L),
            ),
    )

/** "Shelf 1" … "Shelf 4", one cover each. */
internal fun fourShelves() =
    (1..4).map { n ->
        Shelf(
            id = ShelfId("s$n"),
            name = "Shelf $n",
            description = null,
            isPrivate = false,
            ownerId = "me",
            ownerDisplayName = "Simon",
            bookCount = 3,
            totalDurationSeconds = 36_000L,
            createdAtMs = 0L,
            updatedAtMs = 0L,
            covers = listOf(ShelfCover("c$n", coverPath = "/tmp/cover-c$n.webp", coverHash = "h$n")),
        )
    }

/** The shell header's geometry (AppHeader.kt): 8dp top, the greeting beside three 48dp actions. */
internal val testAppHeader: AppHeaderSlot = { leading ->
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.screenMargin, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) { leading() }
        Spacer(Modifier.size(width = 144.dp, height = 48.dp))
    }
}

/** One hour and one day, in milliseconds. */
internal const val HOUR = 3_600_000L
internal const val DAY = 24 * HOUR

/** The board's "now": 2025-10-10 12:00 UTC. */
internal const val BOARD_NOW = 1_760_097_600_000L

/** The next book in a series: "Words of Radiance", Stormlight Archive Book 2, 48h 15m. */
internal fun nextBook(
    id: String = "wor",
    title: String = "Words of Radiance",
    sequenceLabel: String? = "2",
) = NextInSeries(
    bookId = id,
    title = title,
    coverPath = "/tmp/cover-$id.webp",
    coverHash = null,
    narratorNames = "Michael Kramer & Kate Reading",
    seriesName = "Stormlight Archive",
    sequenceLabel = sequenceLabel,
    durationMs = 173_700_000L,
)

/** The just-finished kind: "The Way of Kings", 45h 30m, finished an hour before [BOARD_NOW]. */
internal fun finishedSlot(
    id: String = "wok",
    next: NextInSeries? = nextBook(),
    names: List<String> = listOf("Maya", "Tom"),
    more: Int = 0,
    halfStars: Int? = null,
    startedAtMs: Long? = BOARD_NOW - 34 * DAY,
    finishedAtMs: Long = BOARD_NOW - HOUR,
) = HomeSlot.JustFinished(
    FinishedCard(
        book =
            FinishedBook(
                bookId = id,
                title = "The Way of Kings",
                authorNames = "Brandon Sanderson",
                coverPath = "/tmp/cover-$id.webp",
                coverHash = null,
                durationMs = 163_800_000L,
            ),
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
        yourHalfStars = halfStars,
        next = next,
        alsoFinished = AlsoFinished(names = names, othersCount = more),
    ),
)

/** Nothing in progress, with or without a series candidate. */
internal fun nothingSlot(candidate: NextInSeries? = nextBook()) = HomeSlot.NothingInProgress(candidate)

/** A new listener, with [covers] of the newest books. */
internal fun welcomeSlot(covers: Int = 4) =
    HomeSlot.NewListener(
        covers = (1..covers).map { n -> ShelfCover("new$n", coverPath = "/tmp/cover-new$n.webp", coverHash = null) },
    )
