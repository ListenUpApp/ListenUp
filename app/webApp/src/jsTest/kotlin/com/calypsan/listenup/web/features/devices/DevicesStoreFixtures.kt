package com.calypsan.listenup.web.features.devices

import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedDevices(
    state: DevicesUiState = DevicesUiState.Loading,
    onRevoke: (String) -> Unit = {},
    onSignOutOthers: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenDevices =
    {
        DevicesSession(
            state = MutableStateFlow(state),
            onRevoke = onRevoke,
            onSignOutOthers = onSignOutOthers,
            onRetry = onRetry,
            close = {},
        )
    }
