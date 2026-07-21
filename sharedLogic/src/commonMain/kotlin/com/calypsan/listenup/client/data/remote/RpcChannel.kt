package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.connection.ConnectionEvidence
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService
import org.koin.core.module.Module
import org.koin.core.qualifier.StringQualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.binds
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Default upper bound on a single unary RPC — see [RpcProxyCache]. Long ops override per [RpcChannel.call]. */
internal val DEFAULT_RPC_TIMEOUT: Duration = 15.seconds

/**
 * How a channel recovers a handshake 401.
 *
 * The distinction is load-bearing, not cosmetic: wiring recovery into the anonymous mount would
 * recurse, because the token-refresh primitive ([AuthServicePublic.refreshSession]) itself rides a
 * [Public] channel.
 */
internal enum class RecoveryMode {
    /** Bearer-gated mount: a handshake 401 refreshes the token via [RpcAuthRecovery] and retries once. */
    Authed,

    /** Anonymous mount: never triggers a refresh (the refresh primitive rides a Public channel). */
    Public,
}

/**
 * Everything that varies between channels, expressed as data.
 *
 * A new cross-cutting policy knob (a per-channel circuit-breaker threshold, a metric tag, a retry
 * budget) is a field added here — and every channel, present and future, inherits it with zero
 * per-service edits. That is the whole point of the seam: variance is data, not a class hierarchy.
 */
internal data class RpcPolicy(
    val mount: String,
    val recovery: RecoveryMode,
    val defaultTimeout: Duration = DEFAULT_RPC_TIMEOUT,
) {
    companion object {
        /** The bearer-gated mount every first-party service rides by default. */
        val Authed = RpcPolicy(mount = "/api/rpc/authed", recovery = RecoveryMode.Authed)

        /** The pre-auth mount for the handshake surface (login, register, refresh, invite lookup). */
        val Public = RpcPolicy(mount = "/api/rpc/public", recovery = RecoveryMode.Public)
    }
}

/**
 * The dispatch engine an [RpcChannel] rides. Production is [RpcProxyCache] (bounded, single-flight,
 * self-healing); tests substitute an in-memory double ([RpcChannel.forTest]).
 *
 * Deliberately exposes **no proxy accessor** — unary calls go through [call], subscriptions through
 * [streaming], and there is no third door. That is what makes "reach past the engine" impossible to
 * express, not merely discouraged.
 */
internal interface RpcDispatch<S : Any> : RemoteCache {
    /**
     * Bounded, single-flight, self-healing unary dispatch. See [RpcProxyCache.call].
     *
     * [idempotent] declares that re-firing [block] cannot change server state or double-apply — set
     * it `true` only for READS. When `true`, a post-delivery lost response auto-retries once; when
     * `false` (the default, for every mutation) a lost response surfaces as outcome-unknown, never
     * re-fired.
     */
    suspend fun <R> call(
        timeout: Duration = DEFAULT_RPC_TIMEOUT,
        idempotent: Boolean = false,
        block: suspend (S) -> R,
    ): R

    /** Cold server-push subscription with subscription-time healing. See [RpcProxyCache.streaming]. */
    fun <R> streaming(subscribe: suspend (S) -> Flow<R>): Flow<R>
}

/**
 * The single type every RPC dispatch flows through — unary and streaming, present and future.
 *
 * There is no raw-proxy accessor: "call the service proxy directly" is a compile error, not a review
 * comment. Every robustness capability the engine gains (bounded timeout, 401-heal, single-flight
 * reconnect, outcome-unknown typing, and whatever comes next) reaches every channel through here for
 * free. Adding a service is one line — [rpcChannel] — with no factory and no hand-rolled result fold.
 *
 * - [call] = engine recovery + the [catchingRpcResult] fold → `AppResult<T>`. A business
 *   [AppResult.Failure] returned by the service passes through untouched.
 * - [stream] = engine subscription healing + typed [RpcEvent.Error] surfacing; collection is never
 *   bounded by a timeout.
 * - [invalidate] joins the [RpcCacheInvalidator] sweep via [RemoteCache].
 */
internal class RpcChannel<S : Any> internal constructor(
    private val dispatch: RpcDispatch<S>,
    private val policy: RpcPolicy,
    // Reachability-evidence sink: every unary outcome on every channel is classified into
    // up/down evidence with zero per-service wiring (the whole point of the single boundary).
    // Nullable only so test fixtures without a connection-health graph can omit it.
    private val evidence: ConnectionEvidence? = null,
) : RemoteCache {
    /**
     * Run one RPC against the service with bounded, self-healing recovery, folding the outcome into
     * an [AppResult]. A business [AppResult.Failure] returned by the service passes through untouched.
     * [timeout] defaults to the policy bound; long operations declare their own, e.g.
     * `call(timeout = 10.minutes) { it.restoreBackup(id) }`.
     *
     * [idempotent] is policy-as-data at the call site: pass `true` only when re-firing [block] cannot
     * change server state or double-apply — i.e. for READS (`getBook`, `searchBooks`, `listBackups`,
     * …). It licenses the engine to auto-retry ONCE on a post-delivery lost response instead of
     * surfacing outcome-unknown. Every mutation keeps the safe default `false`, so a lost response is
     * never blindly re-fired.
     */
    suspend fun <T> call(
        timeout: Duration = policy.defaultTimeout,
        idempotent: Boolean = false,
        block: suspend (S) -> AppResult<T>,
    ): AppResult<T> =
        catchingRpcResult { dispatch.call(timeout, idempotent) { service -> block(service) } }
            .also { evidence?.recordOutcome(it) }

    /**
     * Subscribe to a server-pushed stream. Collection is **never** bounded by a timeout.
     *
     * Subscription-time transport faults heal exactly like [call] (401-refresh / reconnect, retried
     * once, only before the first event). A drop after the first event surfaces as one
     * [RpcEvent.Error] followed by normal completion — the consumer's own re-subscribe policy (e.g.
     * the scanner's reconcile loop) decides what to do next, and its next subscription lands on a
     * fresh connection because the engine invalidated the dead generation. A genuine caller
     * cancellation re-raises untouched.
     */
    fun <T> stream(subscribe: suspend (S) -> Flow<RpcEvent<T>>): Flow<RpcEvent<T>> =
        dispatch.streaming(subscribe).catch { e ->
            when {
                e is CancellationException -> {
                    throw e
                }

                // Frame sent, response lost mid-stream: honest, retryable by the consumer's re-subscribe.
                e is RpcOutcomeUnknownException -> {
                    emit(RpcEvent.Error(TransportError.NetworkUnavailable(debugInfo = e.message)))
                }

                else -> {
                    emit(RpcEvent.Error(ErrorMapper.map(e)))
                }
            }
        }

    override suspend fun invalidate() = dispatch.invalidate()

    /** Anchor for test-support extensions (`RpcChannel.forTest`). */
    internal companion object
}

/**
 * The one qualifier scheme for channel definitions: stable, collision-free (each service is a
 * distinct fully-qualified name), and human-readable in Koin's error messages.
 *
 * Koin keys generic singles on the ERASED [RpcChannel] class, so every channel MUST carry this
 * per-service qualifier — two unqualified `RpcChannel<*>` bindings would collide at graph
 * construction. Mirrors the established `consumerSyncHandlerSingle` pattern.
 */
internal inline fun <reified S : Any> rpcChannelQualifier(): StringQualifier =
    named("RpcChannel<${S::class.qualifiedName!!}>")

/**
 * Declare the [RpcChannel] for service [S] in this module — one line per service, no factory, no
 * repository-side fold:
 *
 * ```
 * rpcChannel<GenreService>()                        // authed + self-healing (default)
 * rpcChannel<AuthServicePublic>(RpcPolicy.Public)   // anonymous mount, no recovery
 * ```
 *
 * The `binds arrayOf(RemoteCache::class)` registration is what joins the channel to the
 * [RpcCacheInvalidator] identity sweep automatically — a new channel is dropped on logout / URL
 * change with no call site remembering to list it.
 *
 * This is the **only** production call site of `withService` / `rpc(url)`: raw proxy construction
 * lives here and in the engine alone, pinned by Konsist.
 */
internal inline fun <reified S : Any> Module.rpcChannel(policy: RpcPolicy = RpcPolicy.Authed) {
    single(rpcChannelQualifier<S>()) {
        RpcChannel(
            dispatch =
                RpcProxyCache(
                    apiClientFactory = get(),
                    serverConfig = get<ServerConfig>(),
                    authRecovery =
                        when (policy.recovery) {
                            RecoveryMode.Authed -> get<RpcAuthRecovery>()
                            RecoveryMode.Public -> RpcAuthRecovery.None
                        },
                ) { client, baseUrl -> client.rpc("$baseUrl${policy.mount}").withService<S>() },
            policy = policy,
            evidence = get(),
        )
    } binds arrayOf(RemoteCache::class)
}

/** Resolve the [RpcChannel] for service [S] — the reified type is inferred from the injection site. */
internal inline fun <reified S : Any> Scope.rpcChannel(): RpcChannel<S> = get(rpcChannelQualifier<S>())
