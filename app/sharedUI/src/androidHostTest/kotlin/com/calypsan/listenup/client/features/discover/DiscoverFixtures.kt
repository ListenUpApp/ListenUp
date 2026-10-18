package com.calypsan.listenup.client.features.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfOwner
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module

/** The clock every Discover host test measures relative times against. */
internal const val NOW_MS = 1_000_000_000L

/** A day, in milliseconds. */
internal const val DAY_MS = 24 * 60 * 60 * 1000L

/** An hour, in milliseconds. */
internal const val HOUR_MS = 60 * 60 * 1000L

/** A fresh roster: no snapshot time, not stale. */
internal fun readyListening(rows: List<CurrentlyListeningUiSession>) =
    CurrentlyListeningUiState.Ready(sessions = rows, observedAtMs = null, isStale = false)

/**
 * Avatars resolve their three collaborators through Koin, so a Discover section cannot compose
 * without a container. Stubbed to the "no profile yet" path: the avatar renders a placeholder and
 * never reaches image loading, which keeps each test about what it asserts.
 *
 * The composable KoinApplication registers its container as the global Koin context and never stops
 * it, so every spec that uses this calls `stopKoin()` in an `@After`.
 */
@Composable
internal fun WithAvatarDependencies(content: @Composable () -> Unit) {
    val profiles =
        mock<UserProfileRepository>(MockMode.autoUnit) {
            every { observeProfile(any()) } returns flowOf<CachedUserProfile?>(null)
        }
    val storage =
        mock<ImageStorage>(MockMode.autoUnit) {
            every { userAvatarExists(any()) } returns false
            every { getUserAvatarPath(any()) } returns "/tmp/avatar"
        }
    val images =
        mock<ImageRepository>(MockMode.autoUnit) {
            everySuspend { downloadUserAvatar(any(), any()) } returns AppResult.Success(false)
        }
    KoinApplication(
        configuration =
            koinConfiguration {
                modules(
                    module {
                        single { profiles }
                        single { storage }
                        single { images }
                    },
                )
            },
    ) {
        MaterialTheme { content() }
    }
}

// A local cover path (the file need not exist) keeps BookCoverImage on its synchronous fast path;
// the async fallback reaches for the GLOBAL Koin context, which a composition-scoped KoinApplication
// does not satisfy.

internal fun listener(
    userId: String,
    title: String,
    isLive: Boolean,
    lastActiveAt: Long = NOW_MS,
) = CurrentlyListeningUiSession(
    sessionId = "$userId-s",
    userId = userId,
    bookId = "book-$userId",
    bookTitle = title,
    authorName = "An Author",
    coverPath = "/tmp/cover-$userId.webp",
    coverHash = null,
    displayName = "Name $userId",
    lastActiveAt = lastActiveAt,
    isLive = isLive,
)

internal fun pick(
    id: String,
    title: String,
    series: String?,
    sequence: String?,
    durationMs: Long = 88_740_000,
) = DiscoverUiBook(id, title, "Brandon Sanderson", "/tmp/$id.webp", null, series, sequence, durationMs)

internal fun recent(
    id: String,
    title: String,
    createdAt: Long,
) = RecentlyAddedUiBook(id, title, "Kaliane Bradley", "/tmp/$id.webp", null, createdAt)

internal fun entry(
    rank: Int,
    userId: String,
    name: String,
    seconds: Long,
    isYou: Boolean = false,
) = LeaderboardEntry(
    rank = rank,
    userId = userId,
    displayName = name,
    totalSeconds = seconds,
    booksFinished = 0,
    currentStreakDays = 0,
    longestStreakDays = 0,
    isYou = isYou,
)

internal fun discoverState(
    listening: CurrentlyListeningUiState =
        CurrentlyListeningUiState.Ready(listOf(listener("u1", "Mistborn", isLive = true)), null, false),
    picks: DiscoverBooksUiState = DiscoverBooksUiState.Ready(listOf(pick("p1", "Mistborn", "Mistborn", "1"))),
    leaderboard: LeaderboardUiState =
        LeaderboardUiState.Data(LeaderboardSnapshot(listOf(entry(1, "u1", "Maya", 51_600)), emptyList(), emptyList())),
    selection: LeaderboardSelection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
    activity: ActivityFeedUiState = ActivityFeedUiState.Ready(emptyList()),
    shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Ready(emptyList()),
    recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Ready(listOf(recent("r1", "Orbital", NOW_MS))),
    community: CommunitySize = CommunitySize.Others,
    viewer: DiscoverViewer? = DiscoverViewer("me", "Simon", canInvite = true),
) = DiscoverState(listening, picks, leaderboard, selection, activity, shelves, recentlyAdded, community, viewer)

internal fun noActions(
    onBookClick: (String) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onShelfClick: (String) -> Unit = {},
    onShuffle: () -> Unit = {},
    onOpenLibraryNewest: () -> Unit = {},
) = DiscoverActions(
    onBookClick = onBookClick,
    onShelfClick = onShelfClick,
    onUserClick = onUserClick,
    onShuffle = onShuffle,
    onSelectPeriod = {},
    onSelectCategory = {},
    onOpenLibraryNewest = onOpenLibraryNewest,
    onBookLongPress = null,
    onInvite = null,
    onRetryListening = {},
)

internal fun shelf(
    id: String,
    name: String,
    bookCount: Int = 8,
    coverCount: Int = 4,
) = DiscoverShelfUi(
    id = id,
    name = name,
    description = null,
    bookCount = bookCount,
    totalDurationSeconds = 0,
    covers = (1..coverCount).map { ShelfCover("$id-b$it", "/tmp/$id-$it.webp", null) },
)

internal fun shelfOwner(
    id: String,
    name: String,
    vararg shelves: DiscoverShelfUi,
) = DiscoverUserShelves(DiscoverShelfOwner(id, name), shelves.toList())

internal fun activityRow(
    id: String,
    type: String,
    userId: String = "maya",
    name: String = "Maya Chen",
    bookId: String? = "b-$id",
) = ActivityUiModel(
    id = id,
    userId = userId,
    type = type,
    occurredAt = NOW_MS,
    userDisplayName = name,
    bookId = bookId,
    bookTitle = "Piranesi",
    bookAuthorName = "Susanna Clarke",
    bookCoverPath = "/tmp/$id.webp",
    bookCoverHash = null,
    isReread = false,
    durationMs = 2_880_000,
    milestoneValue = 30,
    milestoneUnit = null,
    shelfId = null,
    shelfName = null,
    isYou = false,
)
