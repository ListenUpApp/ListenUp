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
    /**
     * Invalidate every registered [RemoteCache] — including the streaming client.
     *
     * The full sweep: use it when the connection's *identity* changed (logout, user switch, or a
     * genuine server-URL/host change), so every transport — request client, RPC proxies, AND the
     * streaming client — rebuilds against the new identity on its next use.
     */
    suspend fun invalidateAll()

    /**
     * Invalidate the RPC proxy caches + the regular request client, but NOT the streaming client.
     *
     * The scoped sweep for a firehose *reconnect to the same server*: refresh the stale kotlinx.rpc
     * proxies (and the request client they derive from) so the next RPC call rebinds to the live
     * connection, while sparing the streaming client — closing it would abort the very firehose read whose
     * reconnect triggered the sweep, spinning a self-teardown loop.
     */
    suspend fun invalidateRequestCaches()
}

/**
 * Default [RpcCacheInvalidator]. The cache set is assembled automatically by Koin
 * via `getAll<RemoteCache>()` — every single bound with `binds arrayOf(RemoteCache::class)`
 * joins the sweep automatically. A new authed RPC factory joins the sweep the moment
 * its Koin binding declares `binds arrayOf(RemoteCache::class)`; there is no list to maintain.
 */
internal class DefaultRpcCacheInvalidator(
    internal val caches: List<RemoteCache>,
) : RpcCacheInvalidator {
    override suspend fun invalidateAll() {
        logger.debug { "Invalidating ${caches.size} remote connection cache(s) (incl. streaming)" }
        caches.forEach { it.invalidate() }
    }

    override suspend fun invalidateRequestCaches() {
        logger.debug { "Invalidating ${caches.size} remote connection cache(s) (sparing streaming client)" }
        // The ApiClientFactory is the only cache holding a streaming client; every other RemoteCache
        // (RPC proxy caches) has no streaming concern, so a full invalidate() is correct for them.
        caches.forEach { cache ->
            if (cache is ApiClientFactory) cache.invalidateRequestClientOnly() else cache.invalidate()
        }
    }
}
