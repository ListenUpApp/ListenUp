package com.calypsan.listenup.client.data.remote

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Drops every [RemoteCache] in one sweep.
 *
 * The authed RPC proxies and the bearer-configured [ApiClientFactory] each cache
 * a principal-bound connection. On logout — or a re-login as a different user in
 * the same process — those caches would otherwise keep speaking for the previous
 * session (the proxy's WebSocket binds its principal once, at upgrade). Invalidate
 * them so the next call reconnects under the current identity. Also the single
 * source of truth for a server-URL change, replacing the hand-maintained two-cache
 * list that silently missed every other authed proxy.
 */
interface RpcCacheInvalidator {
    /** Invalidate every registered [RemoteCache]. */
    suspend fun invalidateAll()
}

/**
 * Default [RpcCacheInvalidator]. The cache set is the list of every [RemoteCache]
 * the DI module assembles and hands in. A new authed RPC factory must be added to
 * that list (in `Koin.kt`) to join the sweep.
 */
class DefaultRpcCacheInvalidator(
    internal val caches: List<RemoteCache>,
) : RpcCacheInvalidator {
    override suspend fun invalidateAll() {
        logger.debug { "Invalidating ${caches.size} remote connection cache(s)" }
        caches.forEach { it.invalidate() }
    }
}
