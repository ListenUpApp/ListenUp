package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [NetworkMonitor]; tests flip [onlineFlow], [onUnmeteredNetworkFlow] and [onLocalNetworkFlow] directly. */
class FakeNetworkMonitor(
    online: Boolean = true,
    unmetered: Boolean = true,
    localNetwork: Boolean = unmetered,
) : NetworkMonitor {
    override val onlineFlow = MutableStateFlow(online)

    override val onUnmeteredNetworkFlow = MutableStateFlow(unmetered)

    override val onLocalNetworkFlow = MutableStateFlow(localNetwork)

    override fun isOnline(): Boolean = onlineFlow.value
}
