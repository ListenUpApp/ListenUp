package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a projection that never changes — the shape specs use in place of the graph. */
fun fixedConnectionHealth(
    state: ConnectionHealthUi = ConnectionHealthUi.Hidden,
    onDismiss: () -> Unit = {},
): OpenConnectionHealth =
    { ConnectionHealthSession(state = MutableStateFlow(state), onDismiss = onDismiss, close = {}) }
