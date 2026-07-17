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
 *   cancellation. Our own [TimeoutCancellationException] heals for the next call but never retries;
 *   the frame was sent, so it surfaces as the non-retryable [RpcOutcomeUnknownException], symmetric
 *   with the retry leg's [surface] path.
 *
 * **Drop-proxy vs close-client (C1).** A timeout is our own bound tripping on a possibly-healthy
 * socket, so it drops ONLY the cached proxy ([invalidateProxyOnly]) and re-leases on the SAME shared
 * client for the next call — sibling in-flight calls/streams on this channel are not torn down.
 * Closing the derived [HttpClient] ([invalidate]/[dropLocked]) is reserved for provable socket death
 * (a handshake/WS fault, a dead-client ISE, a from-below post-delivery drop) and for the
 * principal-bound sweep below. Both paths bump the generation, so single-flight is identical.
 *
 * [invalidate] drops the cached proxy and the derived [HttpClient] — they are
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

    /**
     * Wraps a throwable raised by the DOWNSTREAM collector (a `.first()`/`.take(n)` truncation, a
     * Turbine partial-collect abort, or any consumer throw) so the streaming catch clauses can tell
     * it apart from an UPSTREAM transport fault. The [cause] is always re-raised unchanged — a
     * downstream abort must never invalidate a healthy generation or fold to outcome-unknown.
     */
    private class DownstreamEmitException(
        override val cause: Throwable,
    ) : Exception(cause)

    /**
     * Run [block] against the cached proxy with bounded, single-flight, self-healing recovery.
     * See the class KDoc for the full retry/surface policy — the load-bearing invariant is that
     * only **provably pre-delivery** failures retry, so a non-idempotent mutation never double-applies.
     *
     * [idempotent] widens that only for READS the caller declares safe to re-fire: when `true`, a
     * post-delivery lost response (our own first-attempt timeout, or a from-below "Client cancelled")
     * auto-retries ONCE on a fresh lease instead of surfacing outcome-unknown. The retry is
     * at-most-once — a second lost response goes through the ordinary [surface] path. When `false`
     * (the default, every mutation) behaviour is exactly as before: surface, never re-fire.
     */
    override suspend fun <R> call(
        timeout: Duration,
        idempotent: Boolean,
        block: suspend (T) -> R,
    ): R {
        val lease = lease()
        return try {
            withTimeout(timeout) { block(lease.proxy) }
        } catch (e: TimeoutCancellationException) {
            // Our own bound tripped: the frame was SENT and its outcome is unknown. Heal for the NEXT
            // call, never retry, and surface as the non-retryable RpcOutcomeUnknownException (symmetric
            // with surface()'s retry-leg path). Re-raising the raw TCE would fold to a RETRYABLE
            // TransportError.Timeout, inviting a blind Retry that double-applies a committed mutation.
            logger.warn { "RPC timed out after $timeout; frame sent, outcome unknown (no retry)" }
            // Our own bound tripped — that is NO evidence the socket is dead. Drop only the proxy so the
            // NEXT call re-leases, but keep the shared client alive so sibling in-flight calls/streams on
            // this channel are not torn down (C1). Closing here reserved for provable socket death.
            invalidateProxyOnly(lease.generation)
            // A READ is safe to re-fire: retry once (at-most-once — retryOnce's terminal is surface()).
            if (idempotent) return retryOnce(timeout, block)
            throw RpcOutcomeUnknownException(e)
        } catch (e: Throwable) {
            recover(e, lease.generation, timeout, idempotent, block)
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
     * Only an exception from the UPSTREAM iteration ([subscribe] producing/serializing a frame) may
     * be classified as a transport fault and heal. An exception from the DOWNSTREAM [emit] — a
     * truncation (`.first()`, `.take(n)`, a Turbine partial-collect → `AbortFlowException`, which IS
     * a [CancellationException] on a still-active context), or any consumer-side failure — is
     * wrapped by [pipe] in a private marker and re-raised **unchanged** here: it must never
     * invalidate a healthy generation (which would tear down sibling streams/calls on the shared
     * client) nor be rewrapped as [RpcOutcomeUnknownException].
     */
    override fun <R> streaming(subscribe: suspend (T) -> Flow<R>): Flow<R> =
        flow {
            var emitted = false
            val first = lease()
            try {
                pipe(subscribe(first.proxy)) { emitted = true }
            } catch (e: DownstreamEmitException) {
                // A downstream truncation/abort on a HEALTHY generation — propagate the consumer's own
                // throwable unchanged. No invalidate, no OutcomeUnknown rewrap.
                throw e.cause
            } catch (e: Throwable) {
                if (e.isCallerCancellation()) throw e
                invalidate(first.generation)
                when {
                    // A handshake 401 before the first emit heals per the C5 outcome: refresh + resubscribe,
                    // keep-session-retryable on a transient refresh failure, or lapse on a confirmed-dead token.
                    !emitted && isWsHandshake401(e) -> {
                        when (authRecovery.refreshAndRebuild()) {
                            AuthRecoveryOutcome.Refreshed -> resubscribe(subscribe)
                            AuthRecoveryOutcome.Transient -> throw TransientAuthRefreshException(e)
                            AuthRecoveryOutcome.SessionInvalid -> throw e
                        }
                    }

                    canResubscribeStream(e, emitted) -> {
                        resubscribe(subscribe)
                    }

                    else -> {
                        surfaceStreamFailure(e)
                    }
                }
            }
        }

    /**
     * Collect [upstream] into this collector, running [onEmitted] after each successful delivery. An
     * exception from the downstream [emit] is wrapped in [DownstreamEmitException] so the caller can
     * tell a consumer-side abort apart from an UPSTREAM transport fault; an exception from the
     * upstream iteration escapes bare for transport classification.
     */
    private suspend fun <R> FlowCollector<R>.pipe(
        upstream: Flow<R>,
        onEmitted: () -> Unit,
    ) {
        upstream.collect { value ->
            try {
                emit(value)
            } catch (e: Throwable) {
                throw DownstreamEmitException(e)
            }
            onEmitted()
        }
    }

    /** A cancellation whose context is INACTIVE (cancelled) is the CALLER cancelling — re-raise it untouched. */
    private suspend fun Throwable.isCallerCancellation(): Boolean =
        this is CancellationException && !currentCoroutineContext().isActive

    /**
     * Whether a from-below streaming failure can be retried on a fresh lease: only when nothing was
     * emitted yet AND the fault is provably pre-delivery (a pre-delivery transport failure or a
     * dead-client ISE). The handshake-401 case is handled separately at the call site (it must branch
     * on the C5 recovery outcome); everything else is [surfaceStreamFailure]d.
     */
    private fun canResubscribeStream(
        e: Throwable,
        emitted: Boolean,
    ): Boolean = !emitted && (isPreDeliveryTransportFailure(e) || isDeadRpcClient(e))

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
            pipe(subscribe(second.proxy)) { }
        } catch (e: DownstreamEmitException) {
            // Same as the first attempt: a downstream abort is the consumer's business — propagate it
            // unchanged on a healthy generation.
            throw e.cause
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
        idempotent: Boolean,
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

            // A from-below (post-delivery) lost response on a READ the caller declared idempotent: the
            // frame was sent but the response was lost, so re-firing cannot double-apply. Reconnect and
            // retry ONCE (at-most-once — retryOnce's terminal is surface(), so a second loss surfaces).
            idempotent && e.isPostDeliveryLostResponse() -> {
                logger.info { "RPC post-delivery lost response on an idempotent call; reconnecting + retrying once" }
                invalidate(leasedGeneration)
                return retryOnce(timeout, block)
            }

            // Everything else — a from-below (post-delivery) cancellation on a NON-idempotent call, a
            // cancelled caller, or an unknown fault — is NOT safe to retry. Surface it.
            else -> {
                surface(e, leasedGeneration)
            }
        }
    }

    /**
     * A from-below **post-delivery** lost response: a `CancellationException` thrown from below (the
     * WS closed a pending, already-SENT request channel) while the CALLER context is still ACTIVE.
     * Distinguished from a genuine caller cancellation (inactive context) — only this one is safe to
     * re-fire when the call is idempotent.
     */
    private suspend fun Throwable.isPostDeliveryLostResponse(): Boolean =
        this is CancellationException && currentCoroutineContext().isActive

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
        val outcome = authRecovery.refreshAndRebuild()
        invalidate(leasedGeneration)
        return when (outcome) {
            AuthRecoveryOutcome.Refreshed -> {
                retryOnce(timeout, block)
            }

            // C5: a transient refresh failure (network/timeout/5xx) is NOT session death — surface a
            // retryable TransportError and KEEP the session, instead of re-raising the 401 (which maps
            // to SessionExpired → logout). A network blip must never log the user out.
            AuthRecoveryOutcome.Transient -> {
                logger.warn { "Refresh transiently failed during 401-heal; keeping session (retryable)" }
                throw TransientAuthRefreshException(e)
            }

            // Server-confirmed invalid refresh token — surface the 401 so the session lapses.
            AuthRecoveryOutcome.SessionInvalid -> {
                logger.warn { "Refresh token server-confirmed invalid; surfacing the 401 (session lapse)" }
                throw e
            }
        }
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
        if (!callerCancelled) {
            // A retry-leg timeout (our own bound) is no evidence the socket is dead — drop only the proxy
            // so siblings on the shared client survive (C1). Any other fault here is a provable transport
            // drop (a from-below post-delivery cancellation, a WS death) — close the client too.
            if (e is TimeoutCancellationException) {
                invalidateProxyOnly(
                    leasedGeneration,
                )
            } else {
                invalidate(leasedGeneration)
            }
        }
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

    /**
     * Drop ONLY the cached proxy (and bump the generation) if [leasedGeneration] is still current —
     * WITHOUT closing the shared derived [HttpClient]. Used by the timeout paths: our own bound
     * tripping is no evidence the socket is dead, so the next call re-leases a fresh proxy on the SAME
     * shared client while sibling in-flight calls/streams keep running (C1). Single-flight is
     * preserved — the generation bump still converges a herd on one re-lease, exactly like [invalidate].
     */
    private suspend fun invalidateProxyOnly(leasedGeneration: Int) {
        mutex.withLock {
            if (leasedGeneration == generation) dropProxyLocked()
        }
    }

    /** Null the proxy, close the derived RPC client, and bump the generation. Caller holds [mutex]. */
    private fun dropLocked() {
        // Close the derived `.config { }` child so a dead socket's client doesn't leak. It is a
        // child of the shared request client (its engine is shared), so closing it is safe — the
        // request client survives for the next getClient(). Reserved for provable socket death;
        // the timeout paths use dropProxyLocked() so a healthy-but-slow socket's siblings survive.
        cachedRpcClient?.close()
        cachedRpcClient = null
        dropProxyLocked()
    }

    /** Null the proxy and bump the generation, KEEPING the shared client alive. Caller holds [mutex]. */
    private fun dropProxyLocked() {
        cachedProxy = null
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
        else -> throw ServerUrlSchemeUnsupportedException(httpUrl)
    }
