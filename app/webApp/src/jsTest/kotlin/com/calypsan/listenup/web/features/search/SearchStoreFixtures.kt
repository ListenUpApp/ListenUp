package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedSearch(
    state: SearchUiState,
    onToggleType: (SearchHitType) -> Unit = {},
    onClearTypes: () -> Unit = {},
): OpenSearch =
    {
        SearchSession(
            state = MutableStateFlow(state),
            onQueryChanged = {},
            onToggleType = onToggleType,
            onClearTypes = onClearTypes,
            onOpenHit = {},
            retry = {},
            navActions = emptyFlow(),
            close = {},
        )
    }
