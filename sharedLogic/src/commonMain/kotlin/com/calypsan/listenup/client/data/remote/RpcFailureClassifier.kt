package com.calypsan.listenup.client.data.remote

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.websocket.WebSocketException

/**
 * Classifies a throwable that escaped a kotlinx.rpc call so [RpcProxyCache.call] can decide whether
 * to recover the connection and — critically — whether it is safe to auto-retry.
 *
 * The retry contract is **at-most-once**: a call is retried ONLY when the failure proves the RPC
 * frame was never delivered (the WebSocket never opened, or no connection was established). Anything
 * that could have reached a server handler — our own request timeout, a post-connect socket timeout,
 * a serialization or business error — is never retried, so a non-idempotent mutation (createShelf,
 * createCollection) can't double-apply.
 */
internal object RpcFailureClassifier {
    /**
     * A WebSocket handshake that failed with 401: the `/api/rpc/authed` upgrade carried an expired
     * bearer token, so the server answered 401 instead of 101 and Ktor threw a [WebSocketException]
     * ("...expected status code 101 but was 401"). The request never reached a handler — recovery
     * (refresh token → reconnect → retry) is safe.
     *
     * Matching on the exception message is legitimate here: this is a Ktor transport exception, not
     * an [com.calypsan.listenup.api.error.AppError] (the "never substring-match on message" rubric
     * rule governs AppError bodies, not third-party exceptions).
     */
    fun isWsHandshake401(t: Throwable): Boolean =
        t is WebSocketException && (t.message?.contains("401") == true)

    /**
     * A transport failure that proves the RPC frame was never delivered — the WebSocket handshake
     * failed ([WebSocketException]) or the connection was never established ([ConnectTimeoutException]).
     * Safe to invalidate the dead proxy and retry once, even for a non-idempotent mutation.
     * Deliberately excludes post-connect socket timeouts, our own request-timeout, serialization, and
     * business `ResponseException`s — those could have reached a handler.
     */
    fun isPreDeliveryTransportFailure(t: Throwable): Boolean =
        t is WebSocketException || t is ConnectTimeoutException

    /**
     * The cached `RpcClient` (its WebSocket) died — the server restarted, the socket dropped, or the
     * client was otherwise torn down — so kotlinx.rpc refuses the next call, throwing "RpcClient was
     * cancelled". In this kotlinx.rpc version that surfaces as a plain [IllegalStateException] (NOT a
     * [kotlin.coroutines.cancellation.CancellationException]), so the caller-cancellation heuristic
     * can't catch it. The frame was rejected before delivery — invalidate the dead proxy and retry
     * once is safe even for a non-idempotent mutation.
     *
     * Matching on the message is legitimate: this is a kotlinx.rpc transport exception, not an
     * [com.calypsan.listenup.api.error.AppError] (the "never substring-match on message" rule governs
     * AppError bodies, not third-party exceptions).
     */
    fun isDeadRpcClient(t: Throwable): Boolean =
        t.message?.contains("RpcClient was cancelled", ignoreCase = true) == true
}
