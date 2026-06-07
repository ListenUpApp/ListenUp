package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.core.ServerUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Keeps every transport pointed at the live server URL.
 *
 * Observes [activeUrl] (a change-signal from [com.calypsan.listenup.client.domain.repository.ServerConfig])
 * and, when the active URL's host:port genuinely changes, drops all cached connections via
 * [RpcCacheInvalidator.invalidateAll]. RPC proxies, the regular HTTP client, and the SSE/streaming
 * clients all re-read `getActiveUrl()` on their next reconnect, so they follow automatically.
 *
 * Seeded from [initialUrl] so the first emission after a relaunch (same host) does not trigger a
 * spurious invalidation. Null URLs are ignored (disconnected / not yet configured).
 *
 * @param activeUrl reactive active-URL change-signal
 * @param initialUrl authoritative current URL read at start, used to seed the host guard
 * @param invalidator drops all cached remote connections
 * @param scope app-lifetime scope the observer runs on
 */
class ConnectionCoordinator(
    private val activeUrl: StateFlow<ServerUrl?>,
    private val initialUrl: suspend () -> ServerUrl?,
    private val invalidator: RpcCacheInvalidator,
    private val scope: CoroutineScope,
) {
    /** Start the observer. Call once at app start. */
    fun start() {
        scope.launch {
            var lastHost = initialUrl()?.let(::hostKey)
            activeUrl.collect { url ->
                val host = url?.let(::hostKey) ?: return@collect
                if (host != lastHost) {
                    logger.info { "Active URL host changed ($lastHost -> $host); invalidating connections" }
                    lastHost = host
                    invalidator.invalidateAll()
                }
            }
        }
    }
}

/** Stable host:port identity — scheme + trailing slash dropped, lower-cased. */
private fun hostKey(url: ServerUrl): String =
    url.value
        .substringAfter("://")
        .trimEnd('/')
        .lowercase()
