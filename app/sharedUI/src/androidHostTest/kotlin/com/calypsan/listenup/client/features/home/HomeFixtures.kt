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
import com.calypsan.listenup.client.presentation.home.HeroChapter
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.core.ShelfId

// Covers carry a local path so the host tests never reach Koin for a server URL (BookCoverImage's slow path).

/** A Home in its Ready state, every constructor argument named. */
internal fun homeReady(
    slot: HomeSlot,
    alsoInProgress: List<ContinueListeningItem>,
    shelves: List<Shelf> = emptyList(),
) = HomeUiState.Ready(
    userName = "Simon",
    timeGreeting = "",
    continueListening = alsoInProgress,
    myShelves = shelves,
    isSyncing = false,
    isBuildingInitialLibrary = false,
    scanProgress = null,
    timeOfDay = TimeOfDay.Evening,
    firstName = "Simon",
    slot = slot,
    alsoInProgress = alsoInProgress,
    connectivity = HomeConnectivity.Online,
    continueListeningError = null,
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
