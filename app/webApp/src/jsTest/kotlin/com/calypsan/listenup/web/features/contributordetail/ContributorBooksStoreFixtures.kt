package com.calypsan.listenup.web.features.contributordetail

import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedContributorBooks(state: ContributorBooksUiState): OpenContributorBooks =
    { _, _ -> ContributorBooksSession(state = MutableStateFlow(state), close = {}) }
