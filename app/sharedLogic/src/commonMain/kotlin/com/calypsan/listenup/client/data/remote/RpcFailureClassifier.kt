package com.calypsan.listenup.client.data.remote

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.websocket.WebSocketException
import kotlin.coroutines.cancellation.CancellationException

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
    fun isWsHandshake401(t: Throwable): Boolean = t is WebSocketException && t.message?.contains("401") == true

    /**
     * A WebSocket handshake failure on a platform that cannot say what the server answered.
     *
     * [isWsHandshake401] reads the status out of Ktor's exception message, which works wherever the
     * engine upgrades over a real HTTP response. The browser is not such a platform — see
     * [handshakeStatusIsVisible] — so there a 401 from an expired session and an unplugged cable
     * arrive as the same bare [WebSocketException], and the 401 branch can never be taken.
     *
     * Rather than guess, the caller asks: routing this into the same token refresh the 401 branch
     * uses lets the refresh's own three-way outcome name what happened — refreshed, transiently
     * failed (so: really offline, keep the session), or server-confirmed dead (so: lapse and sign
     * in again). Deliberately narrower than [isPreDeliveryTransportFailure]: a
     * `ConnectTimeoutException` never reached a server to be refused by one, so it carries no
     * ambiguity to resolve.
     *
     * [statusIsVisible] defaults to the platform fact and is a parameter only so both branches are
     * reachable from a test on any platform.
     */
    fun isWsHandshakeOfUnknownStatus(
        t: Throwable,
        statusIsVisible: Boolean = handshakeStatusIsVisible,
    ): Boolean = t is WebSocketException && !statusIsVisible

    /**
     * A transport failure that proves the RPC frame was never delivered — the WebSocket handshake
     * failed ([WebSocketException]) or the connection was never established ([ConnectTimeoutException]).
     * Safe to invalidate the dead proxy and retry once, even for a non-idempotent mutation.
     * Deliberately excludes post-connect socket timeouts, our own request-timeout, serialization, and
     * business `ResponseException`s — those could have reached a handler.
     */
    fun isPreDeliveryTransportFailure(t: Throwable): Boolean = t is WebSocketException || t is ConnectTimeoutException

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
        t is IllegalStateException &&
            // On the JVM, kotlin's CancellationException typealiases to java.util.concurrent.
            // CancellationException, which extends IllegalStateException — so the `is IllegalStateException`
            // check alone would misclassify a POST-delivery cancellation as a pre-delivery dead client and
            // license a double-applying retry. Exclude it explicitly.
            t !is CancellationException &&
            t.message?.contains("RpcClient was cancelled", ignoreCase = true) == true
}
