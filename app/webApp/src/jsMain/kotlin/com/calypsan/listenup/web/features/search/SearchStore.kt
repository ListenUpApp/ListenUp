package com.calypsan.listenup.web.features.search

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.client.presentation.search.SearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Search state stream, the gestures the page can fire back into it, and the teardown
 * for it.
 *
 * Mirrors [com.calypsan.listenup.web.features.bookdetail.BookDetailSession]: a browser has no
 * `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it — a session opens when
 * the browser starts showing the search page and closes when it stops. Unlike Book/Contributor
 * Detail, [SearchViewModel] takes no id to load: its state starts at [SearchUiState.Idle] and
 * moves only in response to [onQueryChanged] / [onToggleType], so this session exposes those as
 * plain callbacks rather than handing the page a raw ViewModel to call methods on.
 *
 * [retry] exists because the ViewModel has no dedicated retry entry point: its query pipeline is
 * `distinctUntilChanged`, so resubmitting the same string is a no-op. Clearing the query and
 * resubmitting it is the only way through the ViewModel's own public API to force the exact same
 * search to run again — the honest "try again" for a page whose only knob is the query.
 */
class SearchSession(
    val state: StateFlow<SearchUiState>,
    val onQueryChanged: (String) -> Unit,
    val onToggleType: (SearchHitType) -> Unit,
    val onOpenHit: (SearchHit) -> Unit,
    val retry: () -> Unit,
    val navActions: Flow<SearchNavAction>,
    val close: () -> Unit,
)

/**
 * How the page gets its session. Production resolves the real ViewModel out of the client graph
 * ([graphSearch]); specs hand over a fixed state instead ([fixedSearch]), so the field, chip and
 * five-state contracts can be driven without a database behind them.
 */
typealias OpenSearch = () -> SearchSession

/**
 * The production source: the shared [SearchViewModel], resolved from the started Koin graph.
 *
 * The ViewModel goes into a [ViewModelStore] of its own, for the same reason
 * [com.calypsan.listenup.web.features.bookdetail.graphBookDetail] does: clearing the store is the
 * only sanctioned way to end a ViewModel's `viewModelScope`, and here that scope is one visit to
 * the search page.
 */
fun graphSearch(koin: Koin): OpenSearch =
    {
        val viewModel = koin.get<SearchViewModel>()
        val store = ViewModelStore().apply { put(SEARCH_STORE_KEY, viewModel) }
        SearchSession(
            state = viewModel.state,
            onQueryChanged = viewModel::onQueryChanged,
            onToggleType = viewModel::toggleTypeFilter,
            onOpenHit = viewModel::onResultClicked,
            retry = {
                val currentQuery = viewModel.state.value.query
                viewModel.clearQuery()
                viewModel.onQueryChanged(currentQuery)
            },
            navActions = viewModel.navActions,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedSearch(state: SearchUiState): OpenSearch =
    {
        SearchSession(
            state = MutableStateFlow(state),
            onQueryChanged = {},
            onToggleType = {},
            onOpenHit = {},
            retry = {},
            navActions = emptyFlow(),
            close = {},
        )
    }

private const val SEARCH_STORE_KEY = "search"
