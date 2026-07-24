package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
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
 *    client, and the streaming firehose re-reads `getActiveUrl()` on its next reconnect.
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
 * @param engineState firehose connection state; a reconnect to the same server invalidates the
 *   stale RPC proxy caches (see [start]). Defaults to an idle holder so existing call sites that
 *   don't drive reconnects (tests) need no change; production wires the live engine state.
 */
class ConnectionCoordinator internal constructor(
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val discoveryService: ServerDiscoveryService,
    private val networkMonitor: NetworkMonitor,
    private val invalidator: RpcCacheInvalidator,
    private val scope: CoroutineScope,
    private val engineState: SyncEngineState = SyncEngineState(),
) {
    private val relocationMutex = Mutex()

    private companion object {
        // Must be >= the per-platform mDNS RESOLVE_TIMEOUT (AppleDiscoveryService / NsdDiscoveryService,
        // ~10s): the whole relocate-via-discovery attempt is bounded by this, and its `finally`
        // `stopDiscovery()` aborts any in-flight resolve. At 5s a resolve that legitimately needs ~10s
        // was killed every cycle, so a moved server (DHCP change) never re-resolved and the app stayed
        // stuck offline until relaunch. Keep this in step with the platform resolve timeouts.
        const val RELOCATE_TIMEOUT_MS = 12_000L
    }

    /** Start the observers. Call once at app start. */
    fun start() {
        observeActiveUrl()
        observeNetworkRegain()
        observeFirehoseReconnect()
    }

    /** Re-key connections when the active server URL's host changes. */
    private fun observeActiveUrl() {
        scope.launch {
            var lastHost = serverConfig.getActiveUrl()?.let(::hostKey)
            serverConfig.activeUrl.collect { url ->
                // Guard per item so a transient failure (e.g. invalidateAll) logs and the observer
                // keeps collecting, rather than dying and (on Kotlin/Native) killing the process.
                try {
                    val host = url?.let(::hostKey) ?: return@collect
                    if (host != lastHost) {
                        logger.info { "Active URL host changed ($lastHost -> $host); invalidating connections" }
                        lastHost = host
                        invalidator.invalidateAll()
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Active-URL observer failed; coordinator continues" }
                }
            }
        }
    }

    /** Re-evaluate the reachable server URL when the network comes back. */
    private fun observeNetworkRegain() {
        scope.launch {
            var wasOnline = networkMonitor.isOnline()
            networkMonitor.isOnlineFlow.collect { online ->
                try {
                    if (online && !wasOnline) {
                        logger.info { "Network regained; re-evaluating reachable server URL" }
                        reevaluate()
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Network-regain re-evaluation failed; coordinator continues" }
                }
                wasOnline = online
            }
        }
    }

    /** Sweep stale RPC proxy caches on a firehose reconnect to the same server. */
    private fun observeFirehoseReconnect() {
        scope.launch {
            // A firehose reconnect to the SAME server (host:port unchanged, so the URL observer
            // above never fires) leaves every cached kotlinx.rpc proxy bound to the prior, now-dead
            // RpcClient — so every RPC call throws "RpcClient was cancelled" until the app restarts.
            // Sweep the caches on each reconnect (not the initial connect) so the next RPC call
            // rebinds to the live connection. Never-stranded: recovery without a relaunch.
            var wasConnected = false
            var hasConnectedOnce = false
            engineState.observe().collect { snapshot ->
                val connected = snapshot.connection is ConnectionState.Connected
                try {
                    if (connected && !wasConnected) {
                        if (hasConnectedOnce) {
                            logger.info { "Firehose reconnected; invalidating stale RPC proxy caches" }
                            // SCOPED sweep: refresh the stale RPC proxies + request client, but spare the
                            // streaming client. This reconnect edge fires FROM the streaming client itself;
                            // closing it here would abort the in-flight firehose read and spin a self-teardown
                            // loop (reconnect → invalidate → abort → reconnect …). A genuine host change
                            // takes the full invalidateAll() path in observeActiveUrl() instead.
                            invalidator.invalidateRequestCaches()
                        }
                        hasConnectedOnce = true
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Firehose-reconnect cache sweep failed; coordinator continues" }
                }
                wasConnected = connected
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
