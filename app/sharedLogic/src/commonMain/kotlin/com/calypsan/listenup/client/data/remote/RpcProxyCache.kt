package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isDeadRpcClient
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isPreDeliveryTransportFailure
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isWsHandshake401
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier.isWsHandshakeOfUnknownStatus
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private val logger = KotlinLogging.logger {}

/** Default settle before the single pre-delivery retry — see [RpcProxyCache.preDeliveryRetryBackoff]. */
private val PRE_DELIVERY_RETRY_BACKOFF = 300.milliseconds

/**
 * The shared stateful body of every post-login RPC factory: a Mutex-guarded,
 * invalidate-able, **self-healing** cache of one kotlinx.rpc service proxy, the kotlinx.rpc
 * client (and so the WebSocket) behind it, and the RPC-flavored [HttpClient] derived for it alone.
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
 * **Retire, then close when idle (C1).** A drop RETIRES the live connection — its proxy, its kotlinx.rpc
 * client, and the [HttpClient] derived for it — and bumps the generation, so the next call re-leases a
 * fresh one while single-flight converges a herd on it. A retired connection closes the moment
 * nothing is using it: a [call] holds a use for its attempt, a [streaming] subscription for as long as
 * it is collected. So a timeout — our own bound tripping on a possibly-healthy socket — never tears
 * down the sibling calls and streams still riding that socket, and neither does a provable socket
 * death (they fail on their own) nor the firehose-reconnect sweep ([retire]). The live connection is
 * never closed by its use count. Closing must reach the kotlinx.rpc client AND cancel its [HttpClient]: Ktor's
 * `HttpClient.close()` only completes the client's job, which waits for — never cancels — the
 * WebSocket sessions under it.
 *
 * [invalidate] is the one path that closes connections still in use. A connection is principal-bound
 * and must not survive a logout, re-login, or server-URL change ([RpcCacheInvalidator.invalidateAll]
 * sweeps every [RemoteCache] for exactly that reason), so it retires the live connection and then
 * closes it and every retired one outright: calls and streams still on them fail, and their consumers
 * reconnect under the new identity. Retiring alone would not do — a process-lifetime stream such as
 * scan progress never lets go of its connection.
 *
 * [connect] is where reification lives: `withService<T>()` needs a reified type
 * parameter, so each factory supplies a lambda like
 * `{ client, baseUrl -> client.rpc("$baseUrl/api/rpc/authed").asConnection { withService<FooService>() } }`,
 * handing back the RPC client alongside its proxy so this cache can close it.
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
    /**
     * How long to let a just-invalidated connection settle before the single pre-delivery retry. A
     * pre-delivery transport failure is often a COLD WebSocket opened in a burst against a
     * just-discovered host (the invite-claim "no internet on first tap" shape) — retrying instantly
     * re-hits the same cold host and fails twice. A short settle lets it warm so the retry connects.
     * Injectable so a virtual-time test can drive it deterministically.
     */
    private val preDeliveryRetryBackoff: Duration = PRE_DELIVERY_RETRY_BACKOFF,
    /**
     * Whether a rejected handshake names its status here. Defaults to the platform fact; a
     * parameter only so a test can drive the browser's blindness on any platform.
     */
    private val handshakeStatusVisible: Boolean = handshakeStatusIsVisible,
    private val connect: suspend (rpcClient: HttpClient, wsBaseUrl: String) -> RpcConnection<T>,
) : RpcDispatch<T> {
    /**
     * Whether this cache serves the bearer-gated mount. The public mount is constructed with
     * [RpcAuthRecovery.None], so identity against it is the same discriminator the class already
     * uses to mean "this connection has a session behind it".
     */
    private val isAuthedMount: Boolean get() = authRecovery !== RpcAuthRecovery.None

    private val mutex = Mutex()
    private var current: TrackedConnection<T>? = null

    /** Retired connections still carrying a call or a stream — what [invalidate] must reach. */
    private val retiredInUse = mutableSetOf<TrackedConnection<T>>()

    /**
     * Monotonic connection generation. Every [retireLocked] increments it, so a call that
     * failed on generation G can [retire] its dead proxy without clobbering a proxy a
     * concurrent peer already reconnected (generation G+1). This is the single-flight
     * guarantee: a herd of calls failing on the same generation converges on ONE reconnect.
     */
    private var generation: Int = 0

    /**
     * An [RpcConnection], the RPC-flavoured [httpClient] derived for it alone, and how many calls and
     * streams are using it right now. [retired] once its proxy is dropped; a retired connection closes
     * when [uses] reaches zero, and [closed] makes that happen once. Guarded by [mutex].
     *
     * The client is per connection because the WebSocket upgrade runs in the client's own scope, not
     * the caller's: kotlinx.rpc opens its transport inside the first call, and Ktor's
     * `webSocketSession` launches the upgrade in the HttpClient and hands the caller only a waiter. A
     * call bound that trips mid-upgrade cancels the waiter; the upgrade lands later and parks, where
     * `KtorRpcClient.close()` cannot reach it (its transport never became ready). Cancelling the
     * connection's own client is what kills that orphan — and a client shared across connections could
     * not be cancelled without killing its siblings.
     */
    private class TrackedConnection<T>(
        val connection: RpcConnection<T>,
        val httpClient: HttpClient,
    ) {
        var uses = 0
        var retired = false
        var closed = false
    }

    /**
     * One use of a [TrackedConnection], paired with the [generation] it was leased from so failures
     * invalidate by generation. Every lease is [release]d exactly once, in a `finally`.
     */
    private class Lease<T>(
        val tracked: TrackedConnection<T>,
        val generation: Int,
    ) {
        val proxy: T get() = tracked.connection.proxy
    }

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
        val failure =
            try {
                return withTimeout(timeout) { block(lease.proxy) }
            } catch (e: Throwable) {
                e
            } finally {
                // Released before any healing, so a retry never holds the connection it is leaving.
                release(lease)
            }
        return if (failure is TimeoutCancellationException) {
            timedOut(failure, lease.generation, timeout, idempotent, block)
        } else {
            recover(failure, lease.generation, timeout, idempotent, block)
        }
    }

    /**
     * The first attempt's [TimeoutCancellationException] — checked before [recover] so our own bound
     * is never mistaken for a from-below cancellation.
     */
    private suspend fun <R> timedOut(
        e: TimeoutCancellationException,
        leasedGeneration: Int,
        timeout: Duration,
        idempotent: Boolean,
        block: suspend (T) -> R,
    ): R {
        // Our own bound tripped: the frame was SENT and its outcome is unknown. Heal for the NEXT
        // call, never retry, and surface as the non-retryable RpcOutcomeUnknownException (symmetric
        // with surface()'s retry-leg path). Re-raising the raw TCE would fold to a RETRYABLE
        // TransportError.Timeout, inviting a blind Retry that double-applies a committed mutation.
        logger.warn { "RPC timed out after $timeout; frame sent, outcome unknown (no retry)" }
        // Our own bound tripped — that is NO evidence the socket is dead. Retire the connection so the
        // NEXT call re-leases, while sibling in-flight calls/streams on it are not torn down (C1): it
        // closes once they finish, or at once if there are none.
        retire(leasedGeneration)
        // A READ is safe to re-fire: retry once (at-most-once — retryOnce's terminal is surface()).
        if (idempotent) return retryOnce(timeout, block)
        throw RpcOutcomeUnknownException(e)
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
            val e =
                try {
                    pipe(subscribe(first.proxy)) { emitted = true }
                    null
                } catch (e: DownstreamEmitException) {
                    // A downstream truncation/abort on a HEALTHY generation — propagate the consumer's own
                    // throwable unchanged. No invalidate, no OutcomeUnknown rewrap.
                    throw e.cause
                } catch (e: Throwable) {
                    if (e.isCallerCancellation()) throw e
                    e
                } finally {
                    // Released before any healing, so a resubscribed stream never holds the connection
                    // it moved off — that one closes as soon as the invalidate below retires it.
                    release(first)
                } ?: return@flow
            retire(first.generation)
            when {
                // A handshake 401 before the first emit heals per the C5 outcome: refresh + resubscribe,
                // keep-session-retryable on a transient refresh failure, or lapse on a confirmed-dead token.
                !emitted &&
                    (
                        isWsHandshake401(e) ||
                            (isAuthedMount && isWsHandshakeOfUnknownStatus(e, handshakeStatusVisible))
                    ) -> {
                    when (authRecovery.refreshAndRebuild()) {
                        AuthRecoveryOutcome.Refreshed -> resubscribe(subscribe)
                        AuthRecoveryOutcome.Transient -> throw TransientAuthRefreshException(cause = e)
                        AuthRecoveryOutcome.SessionInvalid -> throw SessionLapsedException(e)
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
            retire(second.generation)
            surfaceStreamFailure(e)
        } finally {
            release(second)
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

            // Same heal, reached the only way the browser allows. There a 401 is invisible, so this
            // arm stands in for the one above and lets the refresh outcome say which it was. Gated
            // on the authed mount: the public one passes RpcAuthRecovery.None, whose refresh is a
            // no-op that reports SessionInvalid, and routing a pre-auth handshake into it would
            // lapse a session over a plain network fault.
            isAuthedMount && isWsHandshakeOfUnknownStatus(e, handshakeStatusVisible) -> {
                logger.info { "RPC handshake failed with no readable status; asking the refresh which it was" }
                return retryAfterAuthRefresh(e, leasedGeneration, timeout, block)
            }

            // Provably pre-delivery: handshake, connect, or a dead-client ISE ("RpcClient was cancelled",
            // thrown BEFORE send). The frame never left — retry cannot double-apply.
            isPreDeliveryTransportFailure(e) || isDeadRpcClient(e) -> {
                logger.info {
                    "RPC pre-delivery transport failure (${e::class.simpleName}); reconnecting + retrying once"
                }
                retire(leasedGeneration)
                // Let a cold/just-opened socket settle before the single retry so a freshly-discovered
                // host isn't hit twice in a burst and failed both times. delay() honours cancellation.
                if (preDeliveryRetryBackoff > Duration.ZERO) delay(preDeliveryRetryBackoff)
                return retryOnce(timeout, block)
            }

            // A from-below (post-delivery) lost response on a READ the caller declared idempotent: the
            // frame was sent but the response was lost, so re-firing cannot double-apply. Reconnect and
            // retry ONCE (at-most-once — retryOnce's terminal is surface(), so a second loss surfaces).
            idempotent && e.isPostDeliveryLostResponse() -> {
                logger.info { "RPC post-delivery lost response on an idempotent call; reconnecting + retrying once" }
                retire(leasedGeneration)
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
     *
     * The refresh itself is bounded to THIS caller's own [timeout] via [withTimeoutOrNull].
     * [RpcAuthRecovery.refreshAndRebuild] rides its own channel (the Public mount) with its own
     * internal bound — left unwrapped, that leg sat entirely OUTSIDE any caller-declared budget: it
     * runs BETWEEN the original attempt's `withTimeout` exiting and [retryOnce]'s `withTimeout`
     * starting, so a caller declaring an 800ms playback-start budget could still pay up to
     * [DEFAULT_RPC_TIMEOUT]'s 15s — doubled to ~30s by the refresh channel's own pre-delivery retry —
     * defeating that budget entirely. A refresh that merely ran out of time is indistinguishable from
     * one this caller's budget says isn't worth waiting for, so it is folded into the SAME outcome as
     * a transient refresh failure below: keep the session, surface retryable. That is strictly safer
     * than either alternative — re-raising the 401 would force a logout over what might be a slow
     * network, and waiting past the caller's own bound is the exact defect this fixes.
     *
     * `withTimeoutOrNull` here bounds the WAIT only, never the WORK: [RpcAuthRecoveryImpl] runs the
     * actual refresh on its own injected scope, independent of this call's coroutine, so this
     * caller giving up does not cancel the refresh — it keeps running for whoever else is waiting, or
     * for the next call to find already done. Wrapping a refresh that ran on THIS coroutine would be
     * an active regression: a network merely slower than [timeout] would cancel every attempt before
     * it could complete, turning transient slowness into a session that can never heal.
     */
    private suspend fun <R> retryAfterAuthRefresh(
        e: Throwable,
        leasedGeneration: Int,
        timeout: Duration,
        block: suspend (T) -> R,
    ): R {
        logger.info { "RPC handshake 401; refreshing token before retry" }
        val outcome =
            withTimeoutOrNull(timeout) { authRecovery.refreshAndRebuild() } ?: run {
                logger.warn { "Auth refresh exceeded the $timeout caller budget; treating as transient" }
                AuthRecoveryOutcome.Transient
            }
        retire(leasedGeneration)
        return when (outcome) {
            AuthRecoveryOutcome.Refreshed -> {
                retryOnce(timeout, block)
            }

            // C5: a transient refresh failure (network/timeout/5xx) is NOT session death — surface a
            // retryable TransportError and KEEP the session, instead of re-raising the 401 (which maps
            // to SessionExpired → logout). A network blip must never log the user out.
            AuthRecoveryOutcome.Transient -> {
                logger.warn { "Refresh transiently failed during 401-heal; keeping session (retryable)" }
                throw TransientAuthRefreshException(cause = e)
            }

            // Server-confirmed invalid refresh token — lapse the session. Typed rather than a
            // re-raised `e`, whose meaning used to be re-derived from a status string the browser
            // never provides; see SessionLapsedException.
            AuthRecoveryOutcome.SessionInvalid -> {
                logger.warn { "Refresh token server-confirmed invalid; surfacing a session lapse" }
                throw SessionLapsedException(e)
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
        } finally {
            release(lease)
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
        // Heal for the next call. Retiring (never force-closing) spares siblings still on the connection
        // (C1) whether this was our own retry-leg timeout or a provable transport drop.
        if (!callerCancelled) retire(leasedGeneration)
        if (e is CancellationException && !callerCancelled) {
            logger.warn { "RPC frame sent but outcome unknown (${e.message}); surfacing as a typed failure (no retry)" }
            throw RpcOutcomeUnknownException(e)
        }
        throw e
    }

    /** Lease the live connection — connecting if there is none — and count this use of it. */
    private suspend fun lease(): Lease<T> =
        mutex.withLock {
            val tracked =
                current ?: run {
                    // Resolve the URL BEFORE deriving the client: a missing server URL
                    // must fail fast (rpcBaseUrl's ServerUrlNotConfiguredException guard)
                    // without caching anything.
                    val wsBaseUrl = rpcBaseUrl()
                    val httpClient = deriveRpcClient()
                    val connection =
                        try {
                            connect(httpClient, wsBaseUrl)
                        } catch (e: Throwable) {
                            // A connect that fails (a socket-ticket mint, say) leaves nothing to own the
                            // client it was handed — cancel it here so its engine reference is released.
                            httpClient.cancel()
                            throw e
                        }
                    TrackedConnection(connection, httpClient).also { current = it }
                }
            tracked.uses++
            Lease(tracked, generation)
        }

    /**
     * End one use of a leased connection, closing it if it is retired and this was its last use.
     * Runs from `finally` blocks, so it takes the mutex [NonCancellable]: a cancelled caller must still
     * give its use back, or its retired connection would never close.
     */
    private suspend fun release(lease: Lease<T>) {
        withContext(NonCancellable) {
            mutex.withLock {
                val tracked = lease.tracked
                tracked.uses--
                if (tracked.retired && tracked.uses == 0) {
                    retiredInUse -= tracked
                    tracked.close()
                }
            }
        }
    }

    /**
     * The identity sweep (logout, re-login, server-URL change): retire the live connection, then close
     * it and every retired one outright, in use or not. Work still riding them fails — a call or stream
     * must not go on speaking for an identity that has changed, and a process-lifetime stream (scan
     * progress) would otherwise never let go. Its release later finds the connection already closed.
     */
    override suspend fun invalidate() {
        mutex.withLock {
            retireLocked()
            retiredInUse.forEach { it.close() }
            retiredInUse.clear()
        }
    }

    /**
     * The same-identity sweep (a firehose reconnect): retire the live connection so the next call
     * reconnects, and let the work on it finish — it closes once nothing uses it.
     */
    override suspend fun retire() {
        mutex.withLock { retireLocked() }
    }

    /**
     * Retire the connection ONLY if [leasedGeneration] is still current — the single-flight guard.
     * A late loser (its failure arrived after a peer already reconnected) becomes a no-op.
     */
    private suspend fun retire(leasedGeneration: Int) {
        mutex.withLock {
            if (leasedGeneration == generation) retireLocked()
        }
    }

    /**
     * Retire the live connection and bump the generation. Caller holds [mutex]. The retired connection
     * closes now if nothing is using it, else on its last [release].
     */
    private fun retireLocked() {
        current?.let { tracked ->
            tracked.retired = true
            if (tracked.uses == 0) tracked.close() else retiredInUse += tracked
        }
        current = null
        generation++
    }

    /**
     * Close this connection, once: the RPC client (a graceful close of a ready transport), then its own
     * HttpClient — cancelled, not just closed, because Ktor's `close()` only completes the client's
     * job and waits for what runs under it, while an upgrade still in flight must be killed.
     *
     * Cancelling is safe for the shared engine: a derived client holds its own reference to it, and
     * dropping that reference closes the engine only when no other client still holds one.
     *
     * Never throws into the caller whose release or drop triggered it — a close that fails is logged
     * and the socket abandoned.
     */
    private fun TrackedConnection<T>.close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Closing a retired RPC connection failed; cancelling its client regardless" }
        } finally {
            httpClient.close()
            httpClient.cancel()
        }
    }

    /**
     * A fresh RPC-flavoured child of the shared request client, for one connection. It shares the
     * request client's engine; closing it leaves the request client and its engine to the others.
     */
    private suspend fun deriveRpcClient(): HttpClient =
        apiClientFactory
            .getClient()
            .config {
                installKrpc {
                    serialization { json(contractJson) }
                }
            }

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
