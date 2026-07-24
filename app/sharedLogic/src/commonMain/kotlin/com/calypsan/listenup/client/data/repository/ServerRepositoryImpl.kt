package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.discovery.DiscoveredServer
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.domain.model.Server
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maps live mDNS discovery into the server-picker list.
 *
 * Each advertised server becomes one [ServerWithStatus] marked online. The list is deduped by the
 * server's stable mDNS TXT id (see [DiscoveredServer.id]) so a multi-homed server — advertising the
 * same id on more than one address (IPv4 + IPv6, wifi + ethernet) — collapses to a single entry.
 * The active server's identity and auth state live in `SecureStorage`, not here; this repository
 * holds no persistence and follows no IP changes.
 *
 * @param discoveryService Platform-specific mDNS discovery
 */
internal class ServerRepositoryImpl(
    private val discoveryService: ServerDiscoveryService,
) : ServerRepository {
    override fun observeServers(): Flow<List<ServerWithStatus>> =
        discoveryService.discover().map { discovered ->
            // Dedup by stable mDNS id, keeping the freshest (last-seen) advertisement per server.
            discovered
                .associateBy { it.id }
                .values
                .map { ServerWithStatus(server = it.toServer(), isOnline = true) }
        }

    override fun startDiscovery() {
        discoveryService.startDiscovery()
    }

    override fun stopDiscovery() {
        discoveryService.stopDiscovery()
    }
}

/**
 * Project a live discovery result into the domain [Server] the picker renders.
 */
private fun DiscoveredServer.toServer(): Server =
    Server(
        id = id,
        name = name,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        localUrl = localUrl,
        remoteUrl = remoteUrl,
        isActive = false,
        lastSeenAt = currentEpochMilliseconds(),
        localUrls = localUrls,
    )
