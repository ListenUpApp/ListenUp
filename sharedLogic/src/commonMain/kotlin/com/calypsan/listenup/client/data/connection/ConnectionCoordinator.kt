package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Keeps every transport pointed at the live server URL.
 *
 * Two responsibilities:
 *  - **Follow:** observes [ServerConfig.activeUrl] and, on a genuine host:port change, drops all
 *    cached connections via [RpcCacheInvalidator.invalidateAll]; RPC proxies, the regular HTTP
 *    client, and SSE/streaming re-read `getActiveUrl()` on their next reconnect.
 *  - **Choose:** [reevaluate] probes the candidate URLs (local then remote) and switches the active
 *    URL to the first reachable, preferring LAN. Runs on app foreground and network-regain.
 *
 * Offline-first: if nothing is reachable, the current URL is kept (never cleared, never blocks).
 *
 * @param serverConfig URL state + the active-URL change-signal
 * @param instanceRepository reachability probe ([InstanceRepository.findReachableUrl])
 * @param networkMonitor drives the network-regain trigger
 * @param invalidator drops all cached remote connections
 * @param scope app-lifetime scope the observers run on
 */
class ConnectionCoordinator(
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val networkMonitor: NetworkMonitor,
    private val invalidator: RpcCacheInvalidator,
    private val scope: CoroutineScope,
) {
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
     * Probe the candidate URLs (local then remote) and switch the active URL to the first reachable,
     * preferring LAN. No-op if nothing is configured or nothing is reachable (offline-first).
     */
    suspend fun reevaluate() {
        val candidates =
            listOfNotNull(
                serverConfig.getServerUrl()?.value,
                serverConfig.getRemoteUrl()?.value,
            ).distinct()
        if (candidates.isEmpty()) return
        val reachable = instanceRepository.findReachableUrl(candidates)
        if (reachable != null) {
            serverConfig.setActiveUrl(ServerUrl(reachable))
        } else {
            logger.debug { "reevaluate: no candidate reachable; keeping current active URL" }
        }
    }
}

/** Stable host:port identity — scheme + trailing slash dropped, lower-cased. */
private fun hostKey(url: ServerUrl): String =
    url.value
        .substringAfter("://")
        .trimEnd('/')
        .lowercase()
