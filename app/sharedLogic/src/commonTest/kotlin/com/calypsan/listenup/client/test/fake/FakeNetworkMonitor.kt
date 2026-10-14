package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [NetworkMonitor]; tests flip [onlineFlow] and [onUnmeteredNetworkFlow] directly. */
class FakeNetworkMonitor(
    online: Boolean = true,
    unmetered: Boolean = true,
) : NetworkMonitor {
    override val onlineFlow = MutableStateFlow(online)

    override val onUnmeteredNetworkFlow = MutableStateFlow(unmetered)

    override fun isOnline(): Boolean = onlineFlow.value
}
