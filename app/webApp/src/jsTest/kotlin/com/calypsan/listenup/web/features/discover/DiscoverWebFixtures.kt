package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfOwner
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
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
internal fun board(entries: List<LeaderboardEntry>) =
    LeaderboardUiState.Data(LeaderboardSnapshot(entries, entries, entries))

/** An activity row by Ada (u1) about "The Institute" (b1), unless a spec says otherwise. */
internal fun act(
    id: String,
    type: String,
    bookId: String? = "b1",
    shelfId: String? = null,
    shelfName: String? = null,
    isYou: Boolean = false,
) = ActivityUiModel(
    id = id,
    userId = "u1",
    type = type,
    occurredAt = DISCOVER_NOW_MS,
    userDisplayName = "Ada",
    bookId = bookId,
    bookTitle = "The Institute",
    bookAuthorName = "Stephen King",
    bookCoverPath = null,
    bookCoverHash = null,
    isReread = false,
    durationMs = 2_880_000,
    milestoneValue = 7,
    milestoneUnit = null,
    shelfId = shelfId,
    shelfName = shelfName,
    isYou = isYou,
)

/** A Ready feed over [rows]. */
internal fun feed(vararg rows: ActivityUiModel) = ActivityFeedUiState.Ready(rows.toList())

/** A shelf with [count] books and [covers] cover tiles. */
internal fun shelf(
    id: String,
    name: String,
    count: Int,
    covers: Int = 4,
) = DiscoverShelfUi(id, name, null, count, 0, (1..covers).map { ShelfCover("$id-b$it", null, null) })

/** One owner's shelves. */
internal fun owner(
    id: String,
    name: String,
    vararg shelves: DiscoverShelfUi,
) = DiscoverUserShelves(DiscoverShelfOwner(id, name), shelves.toList())

/** A Ready shelves state over [owners]. */
internal fun shelvesOf(vararg owners: DiscoverUserShelves) = DiscoverShelvesUiState.Ready(owners.toList())

/** A recently added book, added at [createdAt]. */
internal fun recentBook(
    id: String,
    createdAt: Long = DISCOVER_NOW_MS,
) = RecentlyAddedUiBook(id, "Book $id", "Kaliane Bradley", null, null, createdAt)

/** A Ready listeners state over [sessions]. */
internal fun listening(vararg sessions: CurrentlyListeningUiSession) =
    CurrentlyListeningUiState.Ready(sessions.toList(), observedAtMs = null, isStale = false)

/** A fresh roster over [rows]: no snapshot time, not stale. */
internal fun readyListening(rows: List<CurrentlyListeningUiSession>) =
    CurrentlyListeningUiState.Ready(rows, observedAtMs = null, isStale = false)

/** Two people tied at the top on time (both rank 1), then the next distinct value at rank 3. */
internal fun tiedAtTheTop() =
    leaders(count = 3).mapIndexed { index, entry -> if (index == 1) entry.copy(rank = 1) else entry }

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
    onOpenLibraryNewest: () -> Unit = {},
    onRetryListening: () -> Unit = {},
    onRetryRecent: () -> Unit = {},
    onRetryShelves: () -> Unit = {},
    onRefreshLeaderboard: () -> Unit = {},
    onRefreshActivity: () -> Unit = {},
    communitySize: CommunitySize = CommunitySize.Others,
    viewer: DiscoverViewer? = DiscoverViewer("me", "Simon", canInvite = true),
    onInvite: (() -> Unit)? = {},
    onBrowseLibrary: (() -> Unit)? = {},
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
        onRetryListening = onRetryListening,
        onRetryRecent = onRetryRecent,
        onRetryShelves = onRetryShelves,
        onRefreshLeaderboard = onRefreshLeaderboard,
        onRefreshActivity = onRefreshActivity,
        onShuffle = onShuffle,
        onOpenLibraryNewest = onOpenLibraryNewest,
        communitySize = communitySize,
        viewer = viewer,
        onInvite = onInvite,
        onBrowseLibrary = onBrowseLibrary,
        selection = selection,
    )
}
