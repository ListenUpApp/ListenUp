package com.calypsan.listenup.web.features.shelf

import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedMyShelves(
    state: MyShelvesUiState = MyShelvesUiState.Loading,
    onRetry: () -> Unit = {},
): OpenMyShelves = { MyShelvesSession(state = MutableStateFlow(state), onRetry = onRetry, close = {}) }
