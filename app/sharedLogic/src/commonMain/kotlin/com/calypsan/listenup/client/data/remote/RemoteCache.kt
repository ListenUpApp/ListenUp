package com.calypsan.listenup.client.data.remote

/**
 * A remote-connection cache that can be dropped on demand.
 *
 * Every RPC factory and the shared [ApiClientFactory] cache a live, principal-
 * bound connection (a kotlinx.rpc proxy / WebSocket, or the bearer-configured
 * [io.ktor.client.HttpClient]). Those caches must be invalidated whenever the
 * identity behind them changes — a logout, a re-login as a different user, or a
 * server-URL change — otherwise a stale socket keeps speaking for the previous
 * session. Implementing this marker lets [RpcCacheInvalidator] discover and drop
 * every such cache in one sweep, instead of each call site remembering to list
 * them by hand.
 */
interface RemoteCache {
    /**
     * Drop the cached connection(s) and close them outright — including any still carrying a call or
     * a stream — so nothing keeps speaking for an identity that has changed. The next call reconnects
     * fresh.
     */
    suspend fun invalidate()

    /**
     * Drop the cached connection(s) so the next call reconnects fresh, but let work already riding
     * them finish: each closes once nothing is using it. For a sweep on the SAME identity — the
     * firehose-reconnect sweep, which must not abort the firehose that triggered it. Deliberately
     * abstract: for a cache that carries a stream, falling back to [invalidate] would be exactly the
     * force-close this exists to avoid, so every cache states what retiring means for it.
     */
    suspend fun retire()
}
