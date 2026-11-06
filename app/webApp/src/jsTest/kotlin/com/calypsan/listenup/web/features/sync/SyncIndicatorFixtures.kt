package com.calypsan.listenup.web.features.sync

import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSyncIndicator(
    state: SyncIndicatorUiState = SyncIndicatorUiState(),
    onRefresh: () -> Unit = {},
): OpenSyncIndicator = { SyncIndicatorSession(state = MutableStateFlow(state), onRefresh = onRefresh, close = {}) }
