package com.calypsan.listenup.web.features.seriesedit

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Series Edit session: the state, the merge candidates, the navigation the ViewModel asks
 * for, and the one channel every change goes back through.
 *
 * The same shape [com.calypsan.listenup.web.features.contributoredit.ContributorEditSession] takes,
 * because the two ViewModels take the same shape: a sealed event for everything the form does, and
 * the merge query as a plain setter riding alongside.
 */
class SeriesEditSession(
    val state: StateFlow<SeriesEditUiState>,
    /** Recomputed only while the merge picker is open; empty otherwise, and capped at 30. */
    val mergeCandidates: StateFlow<List<SeriesCandidate>>,
    val navActions: Flow<SeriesEditNavAction>,
    val onEvent: (SeriesEditUiEvent) -> Unit,
    val onMergeQuery: (String) -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenSeriesEdit = (seriesId: String) -> SeriesEditSession

/**
 * The production source: the shared [SeriesEditViewModel], pointed at [seriesId].
 *
 * `loadSeries` fires here, as every other edit session's load does: the load is what the session
 * IS, and leaving it to the page means every future caller has to remember the same two-step.
 */
fun graphSeriesEdit(koin: Koin): OpenSeriesEdit =
    { seriesId ->
        val viewModel = koin.get<SeriesEditViewModel>()
        val store = ViewModelStore().apply { put(seriesId, viewModel) }
        viewModel.loadSeries(seriesId)
        SeriesEditSession(
            state = viewModel.state,
            mergeCandidates = viewModel.mergeCandidates,
            navActions = viewModel.navActions,
            onEvent = viewModel::onEvent,
            onMergeQuery = viewModel::onMergeQueryChange,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSeriesEdit(
    state: SeriesEditUiState,
    mergeCandidates: List<SeriesCandidate> = emptyList(),
    navActions: Flow<SeriesEditNavAction> = emptyFlow(),
    onEvent: (SeriesEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
): OpenSeriesEdit =
    {
        SeriesEditSession(
            state = MutableStateFlow(state),
            mergeCandidates = MutableStateFlow(mergeCandidates),
            navActions = navActions,
            onEvent = onEvent,
            onMergeQuery = onMergeQuery,
            close = {},
        )
    }
