package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfOwner
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.web.features.admin.entry
import com.calypsan.listenup.web.features.contributoredit.candidate
import com.calypsan.listenup.web.features.discover.DiscoverPage
import com.calypsan.listenup.web.features.home.HomeActions
import com.calypsan.listenup.web.features.home.HomePage
import com.calypsan.listenup.web.features.home.continuing
import com.calypsan.listenup.web.features.home.finishedSlot
import com.calypsan.listenup.web.features.home.nothingSlot
import com.calypsan.listenup.web.features.home.readyHome
import com.calypsan.listenup.web.features.home.scanning
import com.calypsan.listenup.web.features.home.shelf
import com.calypsan.listenup.web.features.home.weekStats
import com.calypsan.listenup.web.features.home.welcomeSlot
import com.calypsan.listenup.web.features.match.failed
import io.kotest.core.spec.style.FunSpec

/**
 * Every class Home and Discover render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class HomeClassContractTest :
    FunSpec({
        test("every class Home and Discover render is defined in the design sheet") {
            assertClassesDefined(homeContractShapes())
        }
    })

private fun homeContractShapes(): Map<String, @Composable () -> Unit> =
    homeShapes1() +
        homeShapes2() +
        discoverShapes()

private fun homeShapes1(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#1: HomePage(HomeUiState.Loading, HomeStatsUiState.Loadi") {
            // Home, in every shape that renders a class of its own: loading, error,
            // and the loaded page across a live scan, a bare sync, an empty shelf and
            // all four stats states. It joins this contract by hand like the pages
            // above — a state nobody lists here is a state whose classes go unchecked.
            HomePage(HomeUiState.Loading, HomeStatsUiState.Loading, HomeActions.None)
        },
        shape("#2: HomePage( HomeUiState.Error(UnexpectedClientError(),") {
            HomePage(
                HomeUiState.Error(UnexpectedClientError(), TimeOfDay.Afternoon),
                HomeStatsUiState.Loading,
                HomeActions.None,
            )
        },
        shape("#3: HomePage(readyHome(), HomeStatsUiState.Loading, Home") {
            HomePage(readyHome(), HomeStatsUiState.Loading, HomeActions.None)
        },
        shape("#4: HomePage(readyHome(), HomeStatsUiState.Empty, HomeAc") {
            HomePage(readyHome(), HomeStatsUiState.Empty, HomeActions.None)
        },
        shape("#5: HomePage( readyHome(), HomeStatsUiState.Error(isRetr") {
            HomePage(
                readyHome(),
                HomeStatsUiState.Error(isRetryable = true),
                HomeActions.None,
            )
        },
        shape("#6: HomePage( readyHome(everythingInProgress = listOf(co") {
            HomePage(
                readyHome(everythingInProgress = listOf(continuing("b1", "The Institute"))),
                weekStats(topGenres = listOf(GenreShare("Fiction", 3), GenreShare("Sci-Fi", 1))),
                HomeActions.None,
            )
        },
        shape("#7: HomePage( readyHome(everythingInProgress = listOf(Co") {
            // A slot whose book has not synced yet — the skeleton card's own classes.
            HomePage(
                readyHome(everythingInProgress = listOf(ContinueListeningItem.Loading("b2"))),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#8: HomePage( readyHome(isBuildingInitialLibrary = true)") {
            HomePage(
                readyHome(isBuildingInitialLibrary = true),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#9: HomePage(readyHome(scanProgress = scanning()), weekS") {
            HomePage(readyHome(scanProgress = scanning()), weekStats(), HomeActions.None)
        },
        shape("#10: HomePage( readyHome(myShelves = listOf(shelf(Finishe") {
            // With shelves, so the row's own classes are checked, and without,
            // so its empty state's are.
            HomePage(
                readyHome(myShelves = listOf(shelf("Finished"))),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#11: HomePage( readyHome(slot = finishedSlot(), everythin") {
            // A slot beside the stats on a wide page; the interim empty state; and a private shelf
            // with its lock and mosaic.
            HomePage(
                readyHome(slot = finishedSlot(), everythingInProgress = emptyList()),
                weekStats(),
                HomeActions.None,
                isWide = true,
            )
        },
        shape("#12: HomePage( readyHome(slot = HomeSlot.NothingInProgres") {
            HomePage(
                readyHome(slot = HomeSlot.NothingInProgress(candidate = null)),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#13: HomePage(readyHome(slot = finishedSlot()), weekStats") {
            // Every slot kind, first sync, the notice and the failed row.
            HomePage(readyHome(slot = finishedSlot()), weekStats(), HomeActions.None, isWide = true)
        },
        shape("#14: HomePage(readyHome(slot = nothingSlot()), weekStats(") {
            HomePage(readyHome(slot = nothingSlot()), weekStats(), HomeActions.None, isWide = true)
        },
        shape("#15: HomePage(readyHome(slot = welcomeSlot(2)), HomeStats") {
            HomePage(readyHome(slot = welcomeSlot(2)), HomeStatsUiState.Empty, HomeActions.None)
        },
    )

private fun homeShapes2(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#16: HomePage( readyHome(slot = HomeSlot.FirstSync, isLib") {
            HomePage(
                readyHome(slot = HomeSlot.FirstSync, isLibraryArriving = true),
                HomeStatsUiState.Loading,
                HomeActions.None,
            )
        },
        shape("#17: HomePage( readyHome(connectivity = HomeConnectivity.") {
            HomePage(
                readyHome(connectivity = HomeConnectivity.ServerUnreachable),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#18: HomePage( readyHome(continueListeningError = Unexpec") {
            HomePage(
                readyHome(continueListeningError = UnexpectedClientError()),
                weekStats(),
                HomeActions.None,
            )
        },
        shape("#19: HomePage( readyHome(myShelves = listOf(shelf(Road tr") {
            HomePage(
                readyHome(myShelves = listOf(shelf("Road trip", isPrivate = true, seconds = 3_600L))),
                weekStats(currentStreakDays = 3, longestStreakDays = 9),
                HomeActions.None,
                isWide = true,
            )
        },
    )

/**
 * Every shape Discover can be in, as callable render blocks.
 *
 * A list rather than a single call because the page has five sections with four states each, and
 * only the populated ones render most of the classes — a contract that only listed the happy path
 * would leave every skeleton, empty and error class unchecked.
 */
private fun discoverShapes(): Map<String, @Composable () -> Unit> {
    val entry =
        LeaderboardEntry(
            rank = 1,
            userId = "u1",
            displayName = "Simon",
            totalSeconds = 7_500,
            booksFinished = 3,
            currentStreakDays = 2,
            longestStreakDays = 9,
            isYou = false,
        )
    val snapshot = LeaderboardSnapshot(time = listOf(entry), books = listOf(entry), streak = listOf(entry))
    val listener =
        CurrentlyListeningUiSession(
            sessionId = "s1",
            userId = "u1",
            bookId = "b1",
            bookTitle = "The Institute",
            authorName = "Stephen King",
            coverPath = null,
            coverHash = null,
            displayName = "Simon",
            lastActiveAt = 0L,
            isLive = true,
        )
    val activity =
        ActivityUiModel(
            id = "a1",
            userId = "u1",
            type = "finished_book",
            occurredAt = 0L,
            userDisplayName = "Simon",
            bookId = "b1",
            bookTitle = "The Institute",
            bookAuthorName = "Stephen King",
            bookCoverPath = null,
            bookCoverHash = null,
            isReread = false,
            durationMs = 0L,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = null,
            shelfName = null,
            isYou = false,
        )
    // A row with no book: the plain-text variant, which renders a different element entirely.
    val joined = activity.copy(id = "a2", type = "user_joined", bookId = null, bookTitle = null)

    fun page(
        books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
        recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
        currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
        leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
        leaderboardSelection: LeaderboardSelection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
        activityState: ActivityFeedUiState = ActivityFeedUiState.Loading,
        shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Loading,
        community: CommunitySize = CommunitySize.Others,
    ): @Composable () -> Unit =
        {
            DiscoverPage(
                books = books,
                recentlyAdded = recentlyAdded,
                currentlyListening = currentlyListening,
                leaderboard = leaderboard,
                leaderboardSelection = leaderboardSelection,
                activity = activityState,
                shelves = shelves,
                nowMs = 0L,
                onOpenBook = {},
                onOpenShelf = {},
                onOpenProfile = {},
                onSelectPeriod = {},
                onSelectCategory = {},
                onRetryListening = {},
                onRetryRecent = {},
                onRetryShelves = {},
                onRefreshLeaderboard = {},
                onRefreshActivity = {},
                onShuffle = {},
                onOpenLibraryNewest = {},
                communitySize = community,
                viewer = DiscoverViewer("me", "Simon", canInvite = true),
                onInvite = {},
                onBrowseLibrary = {},
            )
        }

    return shapes(
        // Every section in its skeleton.
        "discover #1: page()" to page(),
        // Every section in its error.
        "discover #2: page( books = DiscoverBooksUiState.Error" to
            page(
                books = DiscoverBooksUiState.Error(UnexpectedClientError()),
                recentlyAdded = RecentlyAddedUiState.Error(UnexpectedClientError()),
                currentlyListening = CurrentlyListeningUiState.Error(UnexpectedClientError()),
                leaderboard = LeaderboardUiState.Error(UnexpectedClientError()),
                activityState = ActivityFeedUiState.Error(UnexpectedClientError()),
                shelves = DiscoverShelvesUiState.Error(UnexpectedClientError()),
            ),
        // Every section empty.
        "discover #3: page( books = DiscoverBooksUiState.Ready" to
            page(
                books = DiscoverBooksUiState.Ready(emptyList()),
                recentlyAdded = RecentlyAddedUiState.Ready(emptyList()),
                currentlyListening = CurrentlyListeningUiState.Ready(emptyList(), observedAtMs = null, isStale = false),
                leaderboard = LeaderboardUiState.Empty,
                activityState = ActivityFeedUiState.Ready(emptyList()),
                shelves = DiscoverShelvesUiState.Ready(emptyList()),
            ),
        // Only you, a started-everything library, and a stale roster: the states' own classes.
        "discover #4: page( books = DiscoverBooksUiState.AllSt" to
            page(
                books = DiscoverBooksUiState.AllStarted,
                currentlyListening = CurrentlyListeningUiState.Ready(emptyList(), observedAtMs = null, isStale = false),
                community = CommunitySize.JustYou,
            ),
        "discover #5: page( currentlyListening = CurrentlyList" to
            page(
                currentlyListening = CurrentlyListeningUiState.Ready(listOf(listener), observedAtMs = 1L, isStale = true),
            ),
        // Every section populated — where most of the classes actually live.
        "discover #6: page( books = DiscoverBooksUiState.Ready" to
            page(
                books = DiscoverBooksUiState.Ready(listOf(discoverBook())),
                recentlyAdded = RecentlyAddedUiState.Ready(listOf(recentBook())),
                currentlyListening =
                    CurrentlyListeningUiState.Ready(
                        listOf(listener, listener.copy(sessionId = "s2", isLive = false)),
                        observedAtMs = null,
                        isStale = false,
                    ),
                leaderboard = LeaderboardUiState.Data(snapshot = snapshot),
                leaderboardSelection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
                activityState = ActivityFeedUiState.Ready(listOf(activity, joined)),
                shelves =
                    DiscoverShelvesUiState.Ready(
                        listOf(
                            DiscoverUserShelves(
                                user = DiscoverShelfOwner(id = "u1", displayName = "Ada"),
                                shelves =
                                    listOf(
                                        DiscoverShelfUi(
                                            id = "s1",
                                            name = "Comfort reads",
                                            description = null,
                                            bookCount = 4,
                                            totalDurationSeconds = 0,
                                            covers = emptyList(),
                                        ),
                                    ),
                            ),
                        ),
                    ),
            ),
    )
}

private fun discoverBook() =
    DiscoverUiBook(
        id = "b1",
        title = "The Institute",
        authorName = "Stephen King",
        coverPath = null,
        coverHash = null,
        seriesName = null,
        sequenceLabel = null,
        durationMs = 0L,
    )

private fun recentBook() =
    RecentlyAddedUiBook(
        id = "b2",
        title = "Dune",
        authorName = "Frank Herbert",
        coverPath = null,
        coverHash = null,
        createdAt = 0L,
    )
