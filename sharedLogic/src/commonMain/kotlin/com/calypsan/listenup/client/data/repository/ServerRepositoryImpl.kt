package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.ServerEntity
import com.calypsan.listenup.client.domain.model.DiscoveredServer
import com.calypsan.listenup.client.domain.model.Server
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.ServerRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.data.discovery.DiscoveredServer as DataDiscoveredServer

private val logger = KotlinLogging.logger {}

/**
 * Callback interface for server URL changes.
 * Used to notify dependent components when the active server's URL changes.
 */
fun interface ServerUrlChangeListener {
    suspend fun onServerUrlChanged(newUrl: ServerUrl)
}

/**
 * Repository implementation for managing ListenUp servers.
 *
 * Bridges mDNS discovery with local persistence:
 * - Discovery results surface in the list keyed by host:port (one row per physical server,
 *   regardless of mDNS id churn); they are persisted only when the user activates one
 * - Persisted servers show online/offline status based on discovery and follow IP changes
 * - Auth tokens are stored per-server for instant switching
 * - Notifies listeners when active server URL changes (for API client invalidation)
 *
 * @param serverDao Database access for server persistence
 * @param discoveryService Platform-specific mDNS discovery
 * @param scope Coroutine scope for background operations
 * @param urlChangeListener Optional callback when active server URL changes
 */
class ServerRepositoryImpl(
    private val serverDao: ServerDao,
    private val discoveryService: ServerDiscoveryService,
    private val scope: CoroutineScope,
    private val urlChangeListener: ServerUrlChangeListener? = null,
) : ServerRepository {
    override fun observeServers(): Flow<List<ServerWithStatus>> =
        combine(
            serverDao.observeAll().distinctUntilChanged(),
            discoveryService.discover().onStart { emit(emptyList()) }.distinctUntilChanged(),
        ) { persisted, discovered ->
            // Follow IP changes for servers the user already saved: when a persisted server's mDNS
            // id reappears at a new address, refresh its stored URL (and notify the active-server
            // listener). Discovery-only servers are deliberately NOT persisted here — blindly
            // persisting every advertised id is what let the list grow without bound as a host's
            // mDNS id churned across restarts.
            scope.launch { refreshRediscoveredServers(discovered) }

            val onlineKeys = discovered.mapNotNull { hostPortKey(it.localUrl) }.toSet()

            // One row per physical host: collapse historical duplicate rows (same host, stale ids),
            // preferring the active server. A host is online when it is currently advertised.
            val persistedRows =
                persisted
                    .groupBy { hostPortKey(it.localUrl ?: it.remoteUrl) ?: "id:${it.id}" }
                    .values
                    .map { rows -> rows.firstOrNull { it.isActive } ?: rows.first() }
                    .map { server ->
                        ServerWithStatus(
                            server = server.toDomain(),
                            isOnline = hostPortKey(server.localUrl ?: server.remoteUrl) in onlineKeys,
                        )
                    }

            val persistedKeys = persisted.mapNotNull { hostPortKey(it.localUrl ?: it.remoteUrl) }.toSet()

            // Discovered servers not already represented by a persisted row, deduped by host.
            val discoveredRows =
                discovered
                    .associateBy { hostPortKey(it.localUrl) }
                    .values
                    .filter { hostPortKey(it.localUrl) !in persistedKeys }
                    .map { ServerWithStatus(it.toEntity().toDomain(), isOnline = true) }

            persistedRows + discoveredRows
        }

    /**
     * Refreshes the stored URL of any persisted server whose mDNS id is currently advertised, so a
     * saved server (notably the active one) follows its address across DHCP/IP changes. Servers not
     * already persisted are left untouched — they surface in the discovered list and are only
     * written to the database when the user activates one.
     */
    private suspend fun refreshRediscoveredServers(discovered: List<DataDiscoveredServer>) {
        for (disc in discovered) {
            val existing = serverDao.getById(disc.id) ?: continue
            val urlChanged = existing.isActive && existing.localUrl != disc.localUrl
            if (urlChanged) {
                logger.info { "Active server IP changed: ${existing.localUrl} -> ${disc.localUrl}" }
            }
            serverDao.updateFromDiscovery(
                id = disc.id,
                name = disc.name,
                apiVersion = disc.apiVersion,
                serverVersion = disc.serverVersion,
                localUrl = disc.localUrl,
                remoteUrl = disc.remoteUrl,
            )
            if (urlChanged) {
                urlChangeListener?.onServerUrlChanged(ServerUrl(disc.localUrl))
            }
        }
    }

    override fun observeActiveServer(): Flow<Server?> = serverDao.observeActive().map { it?.toDomain() }

    override suspend fun getActiveServer(): Server? = serverDao.getActive()?.toDomain()

    override suspend fun setActiveServer(serverId: String) {
        serverDao.setActive(serverId)
    }

    override suspend fun setActiveServer(discovered: DiscoveredServer) {
        // Ensure server exists in database
        val existing = serverDao.getById(discovered.id)
        if (existing == null) {
            serverDao.upsert(discovered.toEntity())
        } else {
            // Update with latest discovery info
            serverDao.updateFromDiscovery(
                id = discovered.id,
                name = discovered.name,
                apiVersion = discovered.apiVersion,
                serverVersion = discovered.serverVersion,
                localUrl = discovered.localUrl,
                remoteUrl = discovered.remoteUrl,
            )
        }
        serverDao.setActive(discovered.id)
    }

    override suspend fun addManualServer(
        name: String,
        url: String,
    ): Server {
        val id = "manual-${currentEpochMilliseconds()}"
        val entity =
            ServerEntity(
                id = id,
                name = name,
                apiVersion = "v1",
                serverVersion = "unknown",
                localUrl = null,
                remoteUrl = url,
                isActive = false,
                lastSeenAt = 0,
            )
        serverDao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun removeServer(serverId: String) {
        serverDao.deleteById(serverId)
    }

    override fun startDiscovery() {
        discoveryService.startDiscovery()
    }

    override fun stopDiscovery() {
        discoveryService.stopDiscovery()
    }

    // ============================================================
    // Auth token methods (internal, not part of domain interface)
    // ============================================================

    /**
     * Save authentication tokens for the active server.
     */
    suspend fun saveAuthTokens(
        accessToken: String,
        refreshToken: String,
        sessionId: String,
        userId: String,
    ) {
        val active =
            serverDao.getActive()
                ?: error("No active server to save tokens for")
        serverDao.saveAuthTokens(
            serverId = active.id,
            accessToken = accessToken,
            refreshToken = refreshToken,
            sessionId = sessionId,
            userId = userId,
        )
    }

    /**
     * Update only the access token (for token refresh).
     */
    suspend fun updateAccessToken(accessToken: String) {
        val active =
            serverDao.getActive()
                ?: error("No active server to update token for")
        serverDao.updateAccessToken(active.id, accessToken)
    }

    /**
     * Clear authentication tokens for the active server.
     * Preserves the server in the list (soft logout).
     */
    suspend fun clearAuthTokens() {
        val active =
            serverDao.getActive()
                ?: error("No active server to clear tokens for")
        serverDao.clearAuthTokens(active.id)
    }
}

// ============================================================
// Mapping functions
// ============================================================

/**
 * Normalizes a server URL to a stable host:port identity — scheme and trailing slash dropped,
 * lower-cased — so the same physical server is recognized regardless of its (churn-prone) mDNS id.
 * Returns null for a null/blank URL.
 */
private fun hostPortKey(url: String?): String? =
    url
        ?.takeIf { it.isNotBlank() }
        ?.substringAfter("://")
        ?.trimEnd('/')
        ?.lowercase()

/**
 * Convert entity to domain model.
 */
private fun ServerEntity.toDomain(): Server =
    Server(
        id = id,
        name = name,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        localUrl = localUrl,
        remoteUrl = remoteUrl,
        isActive = isActive,
        lastSeenAt = lastSeenAt,
    )

/**
 * Convert domain discovered server to entity.
 */
private fun DiscoveredServer.toEntity(): ServerEntity =
    ServerEntity(
        id = id,
        name = name,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        localUrl = localUrl,
        remoteUrl = remoteUrl,
        isActive = false,
        lastSeenAt = currentEpochMilliseconds(),
    )

/**
 * Convert data-layer discovered server to a persistable entity.
 */
private fun DataDiscoveredServer.toEntity(): ServerEntity =
    ServerEntity(
        id = id,
        name = name,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        localUrl = localUrl,
        remoteUrl = remoteUrl,
        isActive = false,
        lastSeenAt = currentEpochMilliseconds(),
    )
