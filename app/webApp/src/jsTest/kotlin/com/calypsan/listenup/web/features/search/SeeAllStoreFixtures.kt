package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

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
