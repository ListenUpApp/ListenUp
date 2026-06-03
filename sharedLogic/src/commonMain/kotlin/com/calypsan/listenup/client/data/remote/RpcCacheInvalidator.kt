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
 * Default [RpcCacheInvalidator]. The cache set is assembled automatically by Koin
 * via `getAll<RemoteCache>()` — every single bound with `binds arrayOf(RemoteCache::class)`
 * joins the sweep automatically. A new authed RPC factory joins the sweep the moment
 * its Koin binding declares `binds arrayOf(RemoteCache::class)`; there is no list to maintain.
 */
class DefaultRpcCacheInvalidator(
    internal val caches: List<RemoteCache>,
) : RpcCacheInvalidator {
    override suspend fun invalidateAll() {
        logger.debug { "Invalidating ${caches.size} remote connection cache(s)" }
        caches.forEach { it.invalidate() }
    }
}
