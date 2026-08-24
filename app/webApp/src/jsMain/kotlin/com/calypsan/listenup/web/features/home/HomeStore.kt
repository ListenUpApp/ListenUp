package com.calypsan.listenup.web.features.home

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Home session: the two state streams the page reads, and the teardown for them.
 *
 * Home is the only web page backed by TWO ViewModels. They are genuinely separate upstreams —
 * [HomeViewModel] combines user, continue-listening, shelves and sync/scan, while
 * [HomeStatsViewModel] observes locally-computed listening stats — and each has its own loading and
 * failure story. Folding them into one state would mean a slow stats query holding back the
 * greeting and the Continue Listening row, which are the reasons someone opened this page.
 *
 * Both go into ONE [ViewModelStore] so a single [close] ends both scopes; the browser has no store
 * of its own to hand their lifetime to, so the page owns it — the same arrangement
 * [com.calypsan.listenup.web.features.search.SearchSession] makes.
 */
class HomeSession(
    val state: StateFlow<HomeUiState>,
    val stats: StateFlow<HomeStatsUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its session. Production resolves both real ViewModels out of the client graph
 * ([graphHome]); specs hand over fixed states instead ([fixedHome]), so the greeting, the
 * Continue Listening row and the four stats states can be driven without a database behind them.
 */
typealias OpenHome = () -> HomeSession

/**
 * The production source: the shared [HomeViewModel] and [HomeStatsViewModel], resolved from the
 * started Koin graph.
 *
 * Clearing the store is the only sanctioned way to end a ViewModel's `viewModelScope`, and here
 * that scope is one visit to Home.
 */
fun graphHome(koin: Koin): OpenHome =
    {
        val home = koin.get<HomeViewModel>()
        val stats = koin.get<HomeStatsViewModel>()
        val store =
            ViewModelStore().apply {
                put(HOME_STORE_KEY, home)
                put(HOME_STATS_STORE_KEY, stats)
            }
        HomeSession(
            state = home.state,
            stats = stats.uiState,
            close = store::clear,
        )
    }

/** A session over states that never change — the shape specs use in place of the graph. */
fun fixedHome(
    state: HomeUiState,
    stats: HomeStatsUiState = HomeStatsUiState.Loading,
): OpenHome =
    {
        HomeSession(
            state = MutableStateFlow(state),
            stats = MutableStateFlow(stats),
            close = {},
        )
    }

private const val HOME_STORE_KEY = "home"
private const val HOME_STATS_STORE_KEY = "home-stats"
