package com.calypsan.listenup.web.features.search

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/** An open see-all session — every hit of one type for one query. */
class SeeAllSession(
    val state: StateFlow<SeeAllSearchUiState>,
    val onOpenHit: (SearchHit) -> Unit,
    val navActions: Flow<SearchNavAction>,
    val close: () -> Unit,
)

/** How the see-all page gets its state. */
typealias OpenSeeAll = (query: String, type: SearchHitType) -> SeeAllSession

/**
 * The production source: the shared [SeeAllSearchViewModel], pointed at one query and one type.
 *
 * `load` fires here, as every other session's does — the load is what the session IS.
 */
fun graphSeeAll(koin: Koin): OpenSeeAll =
    { query, type ->
        val viewModel = koin.get<SeeAllSearchViewModel>()
        val store = ViewModelStore().apply { put("$type:$query", viewModel) }
        viewModel.load(query, type)
        SeeAllSession(
            state = viewModel.state,
            onOpenHit = viewModel::onResultClicked,
            navActions = viewModel.navActions,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSeeAll(
    state: SeeAllSearchUiState,
    onOpenHit: (SearchHit) -> Unit = {},
    navActions: Flow<SearchNavAction> = emptyFlow(),
    onOpen: (String, SearchHitType) -> Unit = { _, _ -> },
): OpenSeeAll =
    { query, type ->
        onOpen(query, type)
        SeeAllSession(
            state = MutableStateFlow(state),
            onOpenHit = onOpenHit,
            navActions = navActions,
            close = {},
        )
    }
