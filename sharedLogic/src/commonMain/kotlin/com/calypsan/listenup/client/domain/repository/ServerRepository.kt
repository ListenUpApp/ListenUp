package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.DiscoveredServer
import com.calypsan.listenup.client.domain.model.Server
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for server discovery and management.
 *
 * Handles mDNS/Bonjour discovery of ListenUp servers and persists
 * discovered servers for selection.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ServerRepository {
    /**
     * Observe all servers with their online status.
     * Merges persisted servers with discovery state.
     *
     * @return Flow emitting list of servers with online status
     */
    fun observeServers(): Flow<List<ServerWithStatus>>

    /**
     * Observe the currently active server.
     *
     * @return Flow emitting the active server or null
     */
    fun observeActiveServer(): Flow<Server?>

    /**
     * Get the currently active server.
     *
     * @return The server currently being used, or null if none selected
     */
    suspend fun getActiveServer(): Server?

    /**
     * Set a server as the active server by ID.
     *
     * @param serverId ID of the server to make active
     */
    suspend fun setActiveServer(serverId: String)

    /**
     * Set a discovered server as active, persisting it if needed.
     *
     * @param discovered The discovered server to activate
     */
    suspend fun setActiveServer(discovered: DiscoveredServer)

    /**
     * Add a manual server entry.
     *
     * @param name Display name for the server
     * @param url Server URL
     * @return The created server entry
     */
    suspend fun addManualServer(
        name: String,
        url: String,
    ): Server

    /**
     * Remove a server entry.
     *
     * @param serverId ID of the server to remove
     */
    suspend fun removeServer(serverId: String)

    /**
     * Start server discovery via mDNS.
     */
    fun startDiscovery()

    /**
     * Stop server discovery.
     */
    fun stopDiscovery()
}
