package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Renders [SeriesEditPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testSeriesEditPage(
    state: SeriesEditUiState,
    mergeCandidates: List<SeriesCandidate> = emptyList(),
    mergeHistory: MergeHistoryState = MergeHistoryState.Ready(emptyList()),
    onEvent: (SeriesEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
    parentPickerRows: List<ParentPickerRow> = emptyList(),
    addSubSeries: AddSubSeriesUiState = AddSubSeriesUiState.Closed(),
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit = {},
) {
    SeriesEditPage(
        state = state,
        mergeCandidates = mergeCandidates,
        mergeHistory = mergeHistory,
        onEvent = onEvent,
        onMergeQuery = onMergeQuery,
        parentPickerRows = parentPickerRows,
        addSubSeries = addSubSeries,
        onAddSubSeriesEvent = onAddSubSeriesEvent,
    )
}

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSeriesEdit(
    state: SeriesEditUiState,
    mergeCandidates: List<SeriesCandidate> = emptyList(),
    mergeHistory: MergeHistoryState = MergeHistoryState.Ready(emptyList()),
    navActions: Flow<SeriesEditNavAction> = emptyFlow(),
    onEvent: (SeriesEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
    parentPickerRows: List<ParentPickerRow> = emptyList(),
    addSubSeries: AddSubSeriesUiState = AddSubSeriesUiState.Closed(),
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit = {},
): OpenSeriesEdit =
    {
        SeriesEditSession(
            state = MutableStateFlow(state),
            mergeCandidates = MutableStateFlow(mergeCandidates),
            mergeHistory = MutableStateFlow(mergeHistory),
            navActions = navActions,
            onEvent = onEvent,
            onMergeQuery = onMergeQuery,
            parentPickerRows = MutableStateFlow(parentPickerRows),
            addSubSeries = MutableStateFlow(addSubSeries),
            onAddSubSeriesEvent = onAddSubSeriesEvent,
            close = {},
        )
    }
