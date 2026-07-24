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
    /** Drop the cached connection(s); the next call reconnects fresh. */
    suspend fun invalidate()
}
