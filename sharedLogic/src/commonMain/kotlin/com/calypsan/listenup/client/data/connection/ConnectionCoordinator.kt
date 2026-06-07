package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

/**
 * Keeps every transport pointed at the live server URL.
 *
 * Two responsibilities:
 *  - **Follow:** observes [ServerConfig.activeUrl] and, on a genuine host:port change, drops all
 *    cached connections via [RpcCacheInvalidator.invalidateAll]; RPC proxies, the regular HTTP
 *    client, and SSE/streaming re-read `getActiveUrl()` on their next reconnect.
 *  - **Choose:** [reevaluate] prefers the LAN, following the connected server's stable mDNS id to a
 *    new address when its local URL has moved, and falling back to a reachable remote URL otherwise.
 *    Runs on app foreground and network-regain.
 *
 * Offline-first: if nothing is reachable, the current URL is kept (never cleared, never blocks).
 *
 * @param serverConfig URL state + the active-URL change-signal
 * @param instanceRepository reachability probe ([InstanceRepository.findReachableUrl])
 * @param discoveryService brief LAN discovery sweeps used to relocate the connected server
 * @param networkMonitor drives the network-regain trigger
 * @param invalidator drops all cached remote connections
 * @param scope app-lifetime scope the observers run on
 */
class ConnectionCoordinator(
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val discoveryService: ServerDiscoveryService,
    private val networkMonitor: NetworkMonitor,
    private val invalidator: RpcCacheInvalidator,
    private val scope: CoroutineScope,
) {
    private val relocationMutex = Mutex()

    private companion object {
        const val RELOCATE_TIMEOUT_MS = 5_000L
    }

    /** Start the observers. Call once at app start. */
    fun start() {
        scope.launch {
            var lastHost = serverConfig.getActiveUrl()?.let(::hostKey)
            serverConfig.activeUrl.collect { url ->
                val host = url?.let(::hostKey) ?: return@collect
                if (host != lastHost) {
                    logger.info { "Active URL host changed ($lastHost -> $host); invalidating connections" }
                    lastHost = host
                    invalidator.invalidateAll()
                }
            }
        }
        scope.launch {
            var wasOnline = networkMonitor.isOnline()
            networkMonitor.isOnlineFlow.collect { online ->
                if (online && !wasOnline) {
                    logger.info { "Network regained; re-evaluating reachable server URL" }
                    reevaluate()
                }
                wasOnline = online
            }
        }
    }

    /**
     * Pick the live server URL, preferring LAN.
     *  1. If the local URL is reachable, use it.
     *  2. Else, if the connected server's stable mDNS id can be relocated on the LAN (brief discovery
     *     sweep), follow it to the new address and use that.
     *  3. Else fall back to a reachable remote URL.
     *  4. Else keep the current active URL (offline-first — never clears, never blocks).
     */
    suspend fun reevaluate() {
        val local = serverConfig.getServerUrl()?.value
        if (local != null) {
            if (instanceRepository.findReachableUrl(listOf(local)) == local) {
                serverConfig.setActiveUrl(ServerUrl(local))
                return
            }
            val relocated = relocateLocalViaDiscovery()
            if (relocated != null) {
                serverConfig.setActiveUrl(ServerUrl(relocated))
                return
            }
        }
        val remote = serverConfig.getRemoteUrl()?.value
        if (remote != null && instanceRepository.findReachableUrl(listOf(remote)) == remote) {
            serverConfig.setActiveUrl(ServerUrl(remote))
        } else {
            logger.debug { "reevaluate: nothing reachable; keeping current active URL" }
        }
    }

    private suspend fun relocateLocalViaDiscovery(): String? =
        relocationMutex.withLock {
            val connectedId = serverConfig.getConnectedServerId() ?: return@withLock null
            val newLocal = findConnectedLocalUrl(connectedId) ?: return@withLock null
            serverConfig.updateLocalUrl(ServerUrl(newLocal))
            if (instanceRepository.findReachableUrl(listOf(newLocal)) == newLocal) newLocal else null
        }

    private suspend fun findConnectedLocalUrl(connectedId: String): String? {
        discoveryService.startDiscovery()
        return try {
            withTimeoutOrNull(RELOCATE_TIMEOUT_MS) {
                discoveryService
                    .discover()
                    .mapNotNull { servers -> servers.firstOrNull { it.id == connectedId }?.localUrl }
                    .first()
            }
        } finally {
            discoveryService.stopDiscovery()
        }
    }
}

/** Stable host:port identity — scheme + trailing slash dropped, lower-cased. */
private fun hostKey(url: ServerUrl): String =
    url.value
        .substringAfter("://")
        .trimEnd('/')
        .lowercase()
