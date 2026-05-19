package com.calypsan.listenup.client.data.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stub implementation of [ServerDiscoveryService] for JVM desktop.
 *
 * mDNS discovery is not yet implemented on desktop. Users must manually
 * enter the server URL.
 *
 * TODO: Implement JmDNS-based discovery for automatic server detection.
 * See: https://github.com/jmdns/jmdns
 */
class StubDiscoveryService : ServerDiscoveryService {
    private val servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())

    override fun discover(): Flow<List<DiscoveredServer>> = servers

    override fun startDiscovery() {
        // TODO: Implement JmDNS discovery
        // val jmdns = JmDNS.create()
        // jmdns.addServiceListener("_listenup._tcp.local.", listener)
    }

    override fun stopDiscovery() {
        // TODO: Stop JmDNS discovery
        // jmdns.close()
    }
}
