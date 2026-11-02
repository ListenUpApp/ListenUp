package com.calypsan.listenup.web.features.contributoredit

import com.calypsan.listenup.client.presentation.contributoredit.ContributorCandidate
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedContributorEdit(
    state: ContributorEditUiState,
    mergeCandidates: List<ContributorCandidate> = emptyList(),
    navActions: Flow<ContributorEditNavAction> = emptyFlow(),
    onEvent: (ContributorEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
): OpenContributorEdit =
    {
        ContributorEditSession(
            state = MutableStateFlow(state),
            mergeCandidates = MutableStateFlow(mergeCandidates),
            navActions = navActions,
            onEvent = onEvent,
            onMergeQuery = onMergeQuery,
            close = {},
        )
    }
