package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedSeriesDetail(
    state: SeriesDetailUiState,
    addSubSeries: AddSubSeriesUiState = AddSubSeriesUiState.Closed(),
    onToggleSection: (String) -> Unit = {},
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit = {},
): OpenSeriesDetail =
    {
        SeriesDetailSession(
            state = MutableStateFlow(state),
            addSubSeries = MutableStateFlow(addSubSeries),
            onToggleSection = onToggleSection,
            onAddSubSeriesEvent = onAddSubSeriesEvent,
            close = {},
        )
    }
