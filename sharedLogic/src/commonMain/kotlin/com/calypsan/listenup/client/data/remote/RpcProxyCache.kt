package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isDeadRpcClient
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isPreDeliveryTransportFailure
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isWsHandshake401
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Upper bound on a single RPC. Guards against a black-holed WebSocket that never resolves. */
private val DEFAULT_RPC_TIMEOUT = 15.seconds

/**
 * The shared stateful body of every post-login RPC factory: a Mutex-guarded,
 * invalidate-able, **self-healing** cache of one kotlinx.rpc service proxy plus the
 * RPC-flavored [HttpClient] it rides on.
 *
 * `rpc(url)` returns a cold [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens
 * its WebSocket on the first message, so the proxy is cached and reused. When that
 * socket later dies (server restart, token expiry, network blip) kotlinx.rpc rejects
 * the next call — with a `CancellationException("RpcClient was cancelled")`, a
 * handshake [io.ktor.client.plugins.websocket.WebSocketException], or a connect
 * timeout. [call] centralizes bounded, single-flight recovery for exactly those
 * cases: it invalidates the dead proxy and retries **at most once** on a freshly
 * reconnected proxy, so a transient death heals invisibly instead of surfacing as a
 * silent no-op or an unbounded hang.
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
) : RemoteCache {
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
     * Run [block] against the cached proxy with bounded, self-healing recovery.
     *
     * - A [TimeoutCancellationException] (our [timeout] tripped) invalidates the proxy so the
     *   NEXT call reconnects, but never auto-retries — the frame may have reached a handler, so
     *   a non-idempotent mutation must not double-apply.
     * - A [CancellationException] with a still-active caller context is the dead-`RpcClient`
     *   signal (the cancel came from below, not from the caller): invalidate and retry once —
     *   the frame was never delivered, so retry is safe. A cancelled caller context re-raises.
     * - A handshake 401 refreshes the token, rebuilds, and retries once. Any other pre-delivery
     *   transport failure (handshake / connect) invalidates and retries once. Everything else
     *   propagates — it could have reached a handler.
     */
    suspend fun <R> call(
        timeout: Duration = DEFAULT_RPC_TIMEOUT,
        block: suspend (T) -> R,
    ): R {
        val lease = lease()
        return try {
            withTimeout(timeout) { block(lease.proxy) }
        } catch (e: TimeoutCancellationException) {
            // The request may be in flight — heal for the NEXT attempt, but never auto-retry here.
            invalidate(lease.generation)
            throw e
        } catch (e: Throwable) {
            recoverOrRethrow(e, lease.generation, timeout, block)
        }
    }

    /**
     * Decide whether [e] is a recoverable transport death and, if so, invalidate the dead proxy and
     * retry once on a fresh connection. Anything that could have reached a handler — a cancelled
     * caller, or an unknown failure — re-raises unchanged.
     */
    private suspend fun <R> recoverOrRethrow(
        e: Throwable,
        leasedGeneration: Int,
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        when {
            // A CancellationException with a still-active caller is the dead-RpcClient signal: the
            // cancel came from below, rejecting the frame before delivery — recoverable.
            e is CancellationException && currentCoroutineContext().isActive -> Unit

            // The caller's own context was cancelled — genuine cancellation, re-raise.
            e is CancellationException -> throw e

            // Stale-session handshake 401 — refresh the token + rebuild, then retry once.
            isWsHandshake401(e) -> authRecovery.refreshAndRebuild()

            // Any other pre-delivery transport death (handshake, connect, dead client) — retry once.
            isPreDeliveryTransportFailure(e) || isDeadRpcClient(e) -> Unit

            // Reached a handler, or an unknown fault — propagate.
            else -> throw e
        }
        invalidate(leasedGeneration)
        return retryOnce(timeout, block)
    }

    /**
     * The single at-most-once retry. Takes a FRESH lease so a herd converges on the one
     * reconnected proxy. A second failure surfaces: invalidate (unless the caller was cancelled)
     * and re-raise so the boundary maps it to a typed failure.
     */
    private suspend fun <R> retryOnce(
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        val lease = lease()
        return try {
            withTimeout(timeout) { block(lease.proxy) }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) invalidate(lease.generation)
            throw e
        } catch (e: Throwable) {
            invalidate(lease.generation)
            throw e
        }
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
