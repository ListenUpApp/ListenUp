package com.calypsan.listenup.web.features.discover

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Discover session: four independent state streams, the two gestures the page accepts, and
 * the teardown for all of it.
 *
 * Discover is backed by THREE ViewModels, one more than Home, and for the same reason Home needed
 * two: they are genuinely separate upstreams with separate failure stories. A leaderboard query
 * that fails must not blank the "what others are listening to" row, and an activity feed still
 * loading must not hold back the books. Each section renders its own sealed state, so one section
 * failing costs exactly that section.
 *
 * The three go into ONE [ViewModelStore] so a single [close] ends all three scopes — the browser
 * has no store to hand their lifetime to, so the page owns it, exactly as
 * [com.calypsan.listenup.web.features.home.HomeSession] does.
 *
 * The shelves section renders other people's public shelves. It was absent from Discover's first
 * version because a card had nowhere to lead; it arrived with the shelf screens.
 */
class DiscoverSession(
    val books: StateFlow<DiscoverBooksUiState>,
    val recentlyAdded: StateFlow<RecentlyAddedUiState>,
    val currentlyListening: StateFlow<CurrentlyListeningUiState>,
    val shelves: StateFlow<DiscoverShelvesUiState>,
    val leaderboard: StateFlow<LeaderboardUiState>,
    val activity: StateFlow<ActivityFeedUiState>,
    val onSelectPeriod: (LeaderboardPeriod) -> Unit,
    val onSelectCategory: (LeaderboardCategory) -> Unit,
    val close: () -> Unit,
)

/**
 * How the page gets its session. Production resolves all three real ViewModels out of the client
 * graph ([graphDiscover]); specs hand over fixed states ([fixedDiscover]), so every section's
 * loading, empty, error and populated shapes can be driven without a database behind them.
 */
typealias OpenDiscover = () -> DiscoverSession

/**
 * The production source: the three shared ViewModels, resolved from the started Koin graph.
 *
 * Clearing the store is the only sanctioned way to end a ViewModel's `viewModelScope`, and here
 * that scope is one visit to Discover.
 */
fun graphDiscover(koin: Koin): OpenDiscover =
    {
        val discover = koin.get<DiscoverViewModel>()
        val leaderboard = koin.get<LeaderboardViewModel>()
        val activity = koin.get<ActivityFeedViewModel>()
        val store =
            ViewModelStore().apply {
                put(DISCOVER_STORE_KEY, discover)
                put(LEADERBOARD_STORE_KEY, leaderboard)
                put(ACTIVITY_STORE_KEY, activity)
            }
        DiscoverSession(
            books = discover.discoverBooksState,
            recentlyAdded = discover.recentlyAddedState,
            currentlyListening = discover.currentlyListeningState,
            shelves = discover.discoverShelvesState,
            leaderboard = leaderboard.uiState,
            activity = activity.state,
            onSelectPeriod = leaderboard::selectPeriod,
            onSelectCategory = leaderboard::selectCategory,
            close = store::clear,
        )
    }

/** A session over states that never change — the shape specs use in place of the graph. */
fun fixedDiscover(
    books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
    recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
    currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
    shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Loading,
    leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
    activity: ActivityFeedUiState = ActivityFeedUiState.Loading,
    onSelectPeriod: (LeaderboardPeriod) -> Unit = {},
    onSelectCategory: (LeaderboardCategory) -> Unit = {},
): OpenDiscover =
    {
        DiscoverSession(
            books = MutableStateFlow(books),
            recentlyAdded = MutableStateFlow(recentlyAdded),
            currentlyListening = MutableStateFlow(currentlyListening),
            shelves = MutableStateFlow(shelves),
            leaderboard = MutableStateFlow(leaderboard),
            activity = MutableStateFlow(activity),
            onSelectPeriod = onSelectPeriod,
            onSelectCategory = onSelectCategory,
            close = {},
        )
    }

private const val DISCOVER_STORE_KEY = "discover"

private const val LEADERBOARD_STORE_KEY = "discover-leaderboard"

private const val ACTIVITY_STORE_KEY = "discover-activity"
