package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes, for specs. */
fun fixedActivityFeed(
    state: ActivityFeedUiState = ActivityFeedUiState.Loading,
    onRefresh: () -> Unit = {},
): OpenActivityFeed = { ActivityFeedSession(MutableStateFlow(state), onRefresh, close = {}) }
