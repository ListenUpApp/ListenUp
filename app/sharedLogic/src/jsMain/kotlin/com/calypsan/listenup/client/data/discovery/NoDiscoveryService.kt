package com.calypsan.listenup.client.data.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Browser "discovery": permanently nothing found, by design rather than by failure.
 *
 * mDNS does not exist in a browser and never will — a web client reaches its server by URL,
 * which is the "Never Stranded" manual fallback the native clients already carry behind
 * discovery. The type still needs a binding because the connection coordinator takes discovery
 * as a constructor input on every platform; this implementation is the truthful one for a
 * platform where the discover list is always empty.
 */
class NoDiscoveryService : ServerDiscoveryService {
    override fun discover(): Flow<List<DiscoveredServer>> = MutableStateFlow(emptyList())

    override fun startDiscovery() {
        // Nothing to start: there is no protocol to speak.
    }

    override fun stopDiscovery() {
        // Nothing to stop.
    }
}
