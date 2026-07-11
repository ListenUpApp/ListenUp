package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isDeadRpcClient
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isPreDeliveryTransportFailure
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isWsHandshake401
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * The shared stateful body of every post-login RPC factory: a Mutex-guarded,
 * invalidate-able, **self-healing** cache of one kotlinx.rpc service proxy plus the
 * RPC-flavored [HttpClient] it rides on.
 *
 * `rpc(url)` returns a cold [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens
 * its WebSocket on the first message, so the proxy is cached and reused. When that
 * socket later dies, [call] centralizes bounded, single-flight recovery — but only for
 * failures it can prove are **pre-delivery** (the frame was never sent), so a retry can
 * never double-apply a non-idempotent mutation:
 *
 * - **Retry once** on a provably pre-delivery signal: an [io.ktor.client.plugins.websocket.WebSocketException]
 *   (handshake), a [io.ktor.client.network.sockets.ConnectTimeoutException], a dead-client
 *   [IllegalStateException] ("RpcClient was cancelled", thrown BEFORE send), or a handshake 401
 *   (after a token refresh).
 * - **Never retry — surface as outcome-unknown** a bare `CancellationException` thrown from below
 *   with a still-active caller ("Client cancelled"): kotlinx.rpc throws this when it closes a
 *   *pending* (already-SENT) request channel, so the mutation may have committed. The engine
 *   converts it to a typed [RpcOutcomeUnknownException] rather than retrying (would double-apply) or
 *   re-raising the raw cancellation (would silently kill the caller's job).
 * - **Re-raise** a bare `CancellationException` with a cancelled caller context — genuine caller
 *   cancellation. Our own [TimeoutCancellationException] heals for the next call but never retries.
 *
 * [invalidate] drops both the cached proxy and the derived [HttpClient] — they are
 * principal-bound and must not survive a logout, re-login, or server-URL change
 * ([RpcCacheInvalidator] sweeps every [RemoteCache] for exactly that reason).
 *
 * [connect] is where reification lives: `withService<T>()` needs a reified type
 * parameter, so each factory supplies a lambda like
 * `{ client, baseUrl -> client.rpc("$baseUrl/api/rpc/authed").withService<FooService>() }`.
 *
 * [authRecovery] refreshes the bearer token and rebuilds the request client when the
 * `/api/rpc/authed` handshake is rejected with 401; the unauthenticated public mount
 * passes [RpcAuthRecovery.None].
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format, two
 * transports.
 */
internal class RpcProxyCache<T : Any>(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
    private val connect: suspend (rpcClient: HttpClient, wsBaseUrl: String) -> T,
) : RpcDispatch<T> {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedProxy: T? = null

    /**
     * Monotonic connection generation. Every [dropLocked] increments it, so a call that
     * failed on generation G can [invalidate] its dead proxy without clobbering a proxy a
     * concurrent peer already reconnected (generation G+1). This is the single-flight
     * guarantee: a herd of calls failing on the same generation converges on ONE reconnect.
     */
    private var generation: Int = 0

    /** A proxy paired with the [generation] it was leased from, so failures invalidate by generation. */
    private data class Lease<T>(
        val proxy: T,
        val generation: Int,
    )

    /** Returns the cached proxy, connecting on first use. */
    suspend fun get(): T = lease().proxy

    /**
     * Run [block] against the cached proxy with bounded, single-flight, self-healing recovery.
     * See the class KDoc for the full retry/surface policy — the load-bearing invariant is that
     * only **provably pre-delivery** failures retry, so a non-idempotent mutation never double-applies.
     */
    override suspend fun <R> call(
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        val lease = lease()
        return try {
            withTimeout(timeout) { block(lease.proxy) }
        } catch (e: TimeoutCancellationException) {
            // Our own bound tripped; the frame may be in flight — heal for the NEXT call, never retry.
            logger.warn { "RPC timed out after $timeout; invalidating for the next attempt (no retry)" }
            invalidate(lease.generation)
            throw e
        } catch (e: Throwable) {
            recover(e, lease.generation, timeout, block)
        }
    }

    /**
     * Subscribe [subscribe] against the cached proxy, with subscription-time healing that mirrors
     * [call]. The retry contract:
     *
     * - A failure BEFORE the first emission is a subscription failure — the server produced nothing
     *   durable, so healing is safe: a handshake 401 refreshes the token and re-subscribes once; a
     *   provably pre-delivery transport fault or dead client reconnects and re-subscribes once.
     * - A failure AFTER the first emission is NEVER auto-resubscribed here — events may have been
     *   missed and replay is domain-specific (the scanner reconciles, import re-fetches status). The
     *   dead generation is invalidated so the consumer's next subscription reconnects fresh, and the
     *   failure is re-raised for [RpcChannel.stream] to fold into a typed
     *   [com.calypsan.listenup.api.streaming.RpcEvent.Error].
     * - A from-below cancellation with a still-active collector (the WS died mid-stream, incl. an
     *   invalidator sweep during logout) becomes [RpcOutcomeUnknownException] — a value at the
     *   boundary, not a silent kill of the collector's job. A genuinely cancelled collector re-raises.
     *
     * [emitted] is flipped before each [emit], so any exception thrown by a DOWNSTREAM collector
     * arrives with [emitted] already true and is re-raised, never mistaken for a subscription fault.
     */
    override fun <R> streaming(subscribe: suspend (T) -> Flow<R>): Flow<R> =
        flow {
            var emitted = false
            val first = lease()
            try {
                subscribe(first.proxy).collect {
                    emitted = true
                    emit(it)
                }
            } catch (e: Throwable) {
                if (e.isCallerCancellation()) throw e
                invalidate(first.generation)
                if (!canResubscribeStream(e, emitted)) surfaceStreamFailure(e)
                resubscribe(subscribe)
            }
        }

    /** A cancellation whose collector is still active is the CALLER cancelling — re-raise it untouched. */
    private suspend fun Throwable.isCallerCancellation(): Boolean =
        this is CancellationException && !currentCoroutineContext().isActive

    /**
     * Whether a from-below streaming failure can be retried on a fresh lease: only when nothing was
     * emitted yet AND the fault is provably pre-delivery (a refreshable handshake 401, a pre-delivery
     * transport failure, or a dead-client ISE). Everything else is [surfaceStreamFailure]d.
     */
    private suspend fun canResubscribeStream(e: Throwable, emitted: Boolean): Boolean =
        !emitted &&
            when {
                isWsHandshake401(e) -> authRecovery.refreshAndRebuild()
                isPreDeliveryTransportFailure(e) || isDeadRpcClient(e) -> true
                else -> false
            }

    /**
     * Turn a non-resubscribable streaming failure into the right thrown signal — always throws.
     * Caller-cancellation is already re-raised upstream, so a remaining from-below cancellation means
     * the frame was sent and its outcome is unknown → a typed [RpcOutcomeUnknownException] value; any
     * other fault re-raises for [RpcChannel.stream] to fold into a typed error.
     */
    private fun surfaceStreamFailure(e: Throwable): Nothing {
        if (e is CancellationException) throw RpcOutcomeUnknownException(e)
        throw e
    }

    /**
     * The single at-most-once stream retry, on a FRESH lease so a herd converges on the one
     * reconnected proxy. Its failure is terminal: a still-active caller cancellation re-raises plain
     * (no invalidate); any other from-below cancellation becomes an outcome-unknown value.
     */
    private suspend fun <R> FlowCollector<R>.resubscribe(subscribe: suspend (T) -> Flow<R>) {
        val second = lease()
        try {
            subscribe(second.proxy).collect { emit(it) }
        } catch (e: Throwable) {
            if (e.isCallerCancellation()) throw e
            invalidate(second.generation)
            surfaceStreamFailure(e)
        }
    }

    /**
     * Retry ONLY on provably pre-delivery signals; otherwise [surface] the failure. Kept separate
     * from [call] so the timeout branch stays first (a [TimeoutCancellationException] must never be
     * mistaken for a from-below cancellation).
     */
    private suspend fun <R> recover(
        e: Throwable,
        leasedGeneration: Int,
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        when {
            // Stale-session handshake 401 — refresh + rebuild, then retry once (or surface if refresh fails).
            isWsHandshake401(e) -> {
                return retryAfterAuthRefresh(e, leasedGeneration, timeout, block)
            }

            // Provably pre-delivery: handshake, connect, or a dead-client ISE ("RpcClient was cancelled",
            // thrown BEFORE send). The frame never left — retry cannot double-apply.
            isPreDeliveryTransportFailure(e) || isDeadRpcClient(e) -> {
                logger.info {
                    "RPC pre-delivery transport failure (${e::class.simpleName}); reconnecting + retrying once"
                }
                invalidate(leasedGeneration)
                return retryOnce(timeout, block)
            }

            // Everything else — a from-below (post-delivery) cancellation, a cancelled caller, or an
            // unknown fault — is NOT safe to retry. Surface it.
            else -> {
                surface(e, leasedGeneration)
            }
        }
    }

    /**
     * Refresh the bearer token for a handshake 401, then retry once on a rebuilt connection. If the
     * refresh fails (tokens cleared), re-raise the original 401 instead of firing a doomed retry —
     * [com.calypsan.listenup.client.core.error.ErrorMapper] maps it to a typed `SessionExpired`.
     */
    private suspend fun <R> retryAfterAuthRefresh(
        e: Throwable,
        leasedGeneration: Int,
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        logger.info { "RPC handshake 401; refreshing token before retry" }
        val refreshed = authRecovery.refreshAndRebuild()
        invalidate(leasedGeneration)
        if (!refreshed) {
            logger.warn { "Token refresh failed; surfacing the 401 instead of retrying" }
            throw e
        }
        return retryOnce(timeout, block)
    }

    /**
     * The single at-most-once retry, on a FRESH lease so a herd converges on the one reconnected
     * proxy. Whatever the retry produces is final — its own failures go through [surface], so a
     * second post-delivery drop becomes an outcome-unknown value, never a re-fired mutation.
     */
    private suspend fun <R> retryOnce(
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        val lease = lease()
        return try {
            withTimeout(timeout) { block(lease.proxy) }
        } catch (e: Throwable) {
            surface(e, lease.generation)
        }
    }

    /**
     * Turn a non-retryable failure into the right thrown signal — always throws.
     *
     * A from-below cancellation (still-active caller, incl. our own timeout on a retry) means the
     * frame was SENT and its outcome is unknown: heal the connection and raise the typed
     * [RpcOutcomeUnknownException] so the boundary folds it to a value (never a re-raised
     * cancellation that would silently kill the caller's job). A genuinely cancelled caller
     * re-raises untouched; any other fault invalidates and re-raises for the boundary to map.
     */
    private suspend fun surface(
        e: Throwable,
        leasedGeneration: Int,
    ): Nothing {
        val callerCancelled = e is CancellationException && !currentCoroutineContext().isActive
        if (!callerCancelled) invalidate(leasedGeneration)
        if (e is CancellationException && !callerCancelled) {
            logger.warn { "RPC frame sent but outcome unknown (${e.message}); surfacing as a typed failure (no retry)" }
            throw RpcOutcomeUnknownException(e)
        }
        throw e
    }

    private suspend fun lease(): Lease<T> =
        mutex.withLock {
            val proxy =
                cachedProxy ?: run {
                    // Resolve the URL BEFORE deriving the client: a missing server URL
                    // must fail fast (rpcBaseUrl's ServerUrlNotConfiguredException guard)
                    // without caching anything.
                    val wsBaseUrl = rpcBaseUrl()
                    connect(rpcClient(), wsBaseUrl).also { cachedProxy = it }
                }
            Lease(proxy, generation)
        }

    override suspend fun invalidate() {
        mutex.withLock { dropLocked() }
    }

    /**
     * Drop the proxy ONLY if [leasedGeneration] is still current — the single-flight guard.
     * A late loser (its failure arrived after a peer already reconnected) becomes a no-op.
     */
    private suspend fun invalidate(leasedGeneration: Int) {
        mutex.withLock {
            if (leasedGeneration == generation) dropLocked()
        }
    }

    /** Null the proxy, close the derived RPC client, and bump the generation. Caller holds [mutex]. */
    private fun dropLocked() {
        cachedProxy = null
        // Close the derived `.config { }` child so a dead socket's client doesn't leak. It is a
        // child of the shared request client (its engine is shared), so closing it is safe — the
        // request client survives for the next getClient().
        cachedRpcClient?.close()
        cachedRpcClient = null
        generation++
    }

    private suspend fun rpcClient(): HttpClient =
        cachedRpcClient ?: apiClientFactory
            .getClient()
            .config {
                installKrpc {
                    serialization { json(contractJson) }
                }
            }.also { cachedRpcClient = it }

    private suspend fun rpcBaseUrl(): String {
        val httpUrl =
            serverConfig.getActiveUrl()?.value
                ?: throw ServerUrlNotConfiguredException()
        return toWebSocketScheme(httpUrl)
    }
}

/**
 * Translate an HTTP-scheme URL into its WebSocket equivalent. kotlinx.rpc
 * 0.10.x's `client.rpc(url)` opens a WebSocket session and does NOT
 * auto-upgrade `http://` → `ws://`; passing the raw HTTP URL produces a
 * plain GET that the server rejects with 400. The translation lives in the
 * RPC layer (not on `ServerConfig`) because the WS scheme is an RPC-transport
 * concern — REST callers want the unmodified URL.
 *
 * Visibility is `internal` so unit tests can pin every branch (this is the
 * regression net for the F12-discovered production bug).
 */
internal fun toWebSocketScheme(httpUrl: String): String =
    when {
        httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
        httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
        httpUrl.startsWith("ws://") || httpUrl.startsWith("wss://") -> httpUrl
        else -> error("Server URL has unsupported scheme: $httpUrl")
    }
