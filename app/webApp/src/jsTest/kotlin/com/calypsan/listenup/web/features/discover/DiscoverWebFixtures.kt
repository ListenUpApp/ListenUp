package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.features.books.BookSelection

/** The clock every Discover browser spec reads relative times against. */
internal const val DISCOVER_NOW_MS = 1_000_000_000L

/** Two days, in milliseconds: far enough back that "2 days ago" is stable to read. */
internal const val DISCOVER_TWO_DAYS_MS = 172_800_000L

/** A listener on "The Institute"; [userId] keys the session, the book and the name. */
internal fun listenerOf(
    userId: String,
    live: Boolean,
    lastActiveAt: Long = DISCOVER_NOW_MS,
    name: String = "Ada",
) = CurrentlyListeningUiSession(
    sessionId = "s-$userId",
    userId = userId,
    bookId = "b-$userId",
    bookTitle = "The Institute",
    authorName = "Stephen King",
    coverPath = null,
    coverHash = null,
    displayName = name,
    lastActiveAt = lastActiveAt,
    isLive = live,
)

/** A pick: [seq] of [series] (or standalone), [ms] long. */
internal fun pickBook(
    id: String,
    series: String?,
    seq: String?,
    ms: Long = 88_740_000,
) = DiscoverUiBook(id, "Book $id", "Brandon Sanderson", null, null, series, seq, ms)

/** [count] people ranked 1..count on time, "Person N" with id "uN"; [youAt] is the reader's rank. */
internal fun leaders(
    count: Int = 9,
    youAt: Int? = null,
) = (1..count).map {
    LeaderboardEntry(
        rank = it,
        userId = "u$it",
        displayName = "Person $it",
        totalSeconds = (count + 1L - it) * 3_600L,
        booksFinished = 0,
        currentStreakDays = 0,
        longestStreakDays = 0,
        isYou = it == youAt,
    )
}

/** A loaded board whose three categories all rank [entries]. */
internal fun board(entries: List<LeaderboardEntry>) = LeaderboardUiState.Data(LeaderboardSnapshot(entries, entries, entries))

/** A Ready listeners state over [sessions]. */
internal fun listening(vararg sessions: CurrentlyListeningUiSession) =
    CurrentlyListeningUiState.Ready(sessions.toList(), observedAtMs = null, isStale = false)

/** [DiscoverPage] with every section Loading unless a spec says otherwise. */
@Composable
internal fun discoverPage(
    books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
    recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
    currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
    leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
    leaderboardSelection: LeaderboardSelection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
    activity: ActivityFeedUiState = ActivityFeedUiState.Loading,
    shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Loading,
    onOpenBook: (String) -> Unit = {},
    onOpenShelf: (String) -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onSelectPeriod: (LeaderboardPeriod) -> Unit = {},
    onSelectCategory: (LeaderboardCategory) -> Unit = {},
    onShuffle: () -> Unit = {},
    selection: BookSelection? = null,
) {
    DiscoverPage(
        books = books,
        recentlyAdded = recentlyAdded,
        currentlyListening = currentlyListening,
        leaderboard = leaderboard,
        leaderboardSelection = leaderboardSelection,
        activity = activity,
        shelves = shelves,
        nowMs = DISCOVER_NOW_MS,
        onOpenBook = onOpenBook,
        onOpenShelf = onOpenShelf,
        onOpenProfile = onOpenProfile,
        onSelectPeriod = onSelectPeriod,
        onSelectCategory = onSelectCategory,
        onRefresh = {},
        onRefreshLeaderboard = {},
        onRefreshActivity = {},
        onShuffle = onShuffle,
        selection = selection,
    )
}
