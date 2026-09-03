package com.calypsan.listenup.web.features.seriesdetail

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Series Detail state stream, plus the teardown for it.
 *
 * Mirrors [com.calypsan.listenup.web.features.contributordetail.ContributorDetailSession]: a
 * browser has no `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it — a
 * session opens when the browser starts showing a series and closes when it stops.
 */
class SeriesDetailSession(
    val state: StateFlow<SeriesDetailUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphSeriesDetail]); specs hand over a fixed state instead ([fixedSeriesDetail]), so the hero,
 * the reading order and the resume target can be driven without a database behind them.
 */
typealias OpenSeriesDetail = (seriesId: String) -> SeriesDetailSession

/**
 * The production source: the shared [SeriesDetailViewModel], resolved from the started Koin graph
 * and pointed at [seriesId].
 *
 * The ViewModel goes into a [ViewModelStore] of its own, for the same reason
 * [com.calypsan.listenup.web.features.bookdetail.graphBookDetail] does: clearing the store is the
 * only sanctioned way to end a ViewModel's `viewModelScope`, and here that scope is one visited
 * series.
 */
fun graphSeriesDetail(koin: Koin): OpenSeriesDetail =
    { seriesId ->
        val viewModel = koin.get<SeriesDetailViewModel>()
        val store = ViewModelStore().apply { put(seriesId, viewModel) }
        viewModel.loadSeries(seriesId)
        SeriesDetailSession(state = viewModel.state, close = store::clear)
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedSeriesDetail(state: SeriesDetailUiState): OpenSeriesDetail =
    { SeriesDetailSession(state = MutableStateFlow(state), close = {}) }
