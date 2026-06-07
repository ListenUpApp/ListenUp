package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.ServerWithStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for live server discovery.
 *
 * Surfaces ListenUp servers currently advertised on the local network via
 * mDNS/Bonjour. Discovery is transient — the active server identity and its
 * auth state live in `SecureStorage` (via `ServerConfig` / `AuthSessionStore`),
 * not here.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ServerRepository {
    /**
     * Observe the servers currently visible on the local network.
     *
     * One entry per physical server (deduped by the server's stable mDNS id),
     * each marked online because it is being actively advertised.
     *
     * @return Flow emitting the live list of discovered servers
     */
    fun observeServers(): Flow<List<ServerWithStatus>>

    /**
     * Start server discovery via mDNS.
     */
    fun startDiscovery()

    /**
     * Stop server discovery.
     */
    fun stopDiscovery()
}
