package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService
import kotlin.time.Duration

/**
 * The write-once test dispatch: in-memory, no network, no reconnect — but timeouts are real
 * ([withTimeout]) and thrown service faults flow through the REAL [catchingRpcResult] / [ErrorMapper]
 * boundary in [RpcChannel]. One double for every service, forever — this replaces the dozens of
 * per-service `Fake*RpcFactory` doubles.
 */
internal class DirectRpcDispatch<S : Any>(
    private val service: S,
) : RpcDispatch<S> {
    override suspend fun <R> call(
        timeout: Duration,
        block: suspend (S) -> R,
    ): R =
        try {
            withTimeout(timeout) { block(service) }
        } catch (e: TimeoutCancellationException) {
            // Mirror the production RpcProxyCache: a post-send bound trip is outcome-unknown, never a
            // retryable Timeout — so forTest-based tests observe the same non-retryable fold.
            throw RpcOutcomeUnknownException(e)
        }

    override fun <R> streaming(subscribe: suspend (S) -> Flow<R>): Flow<R> = flow { emitAll(subscribe(service)) }

    override suspend fun invalidate() = Unit
}

/**
 * Fault-scripting dispatch for engine-adjacent tests (outbox regression, stream-drop): pops one
 * scripted throwable per call/subscribe, in order, before delegating to [service]. Lets a test drive
 * the exact transport faults ([RpcOutcomeUnknownException], a dead-client [IllegalStateException])
 * that production would throw, through the real channel fold.
 */
internal class ScriptedRpcDispatch<S : Any>(
    private val service: S,
    faults: List<Throwable>,
) : RpcDispatch<S> {
    private val remaining = faults.toMutableList()

    override suspend fun <R> call(
        timeout: Duration,
        block: suspend (S) -> R,
    ): R {
        remaining.removeFirstOrNull()?.let { throw it }
        return try {
            withTimeout(timeout) { block(service) }
        } catch (e: TimeoutCancellationException) {
            // Mirror the production RpcProxyCache post-send bound: outcome-unknown, not a retryable Timeout.
            throw RpcOutcomeUnknownException(e)
        }
    }

    override fun <R> streaming(subscribe: suspend (S) -> Flow<R>): Flow<R> =
        flow {
            remaining.removeFirstOrNull()?.let { throw it }
            emitAll(subscribe(service))
        }

    override suspend fun invalidate() = Unit
}

/**
 * THE test channel: `RpcChannel.forTest(mock<GenreService>())`. Routes `call`/`stream` through the
 * production [catchingRpcResult] boundary via [DirectRpcDispatch], so repository tests exercise the
 * real fold semantics (business-Failure passthrough, throw→typed-Failure, cancellation re-raise).
 */
internal fun <S : Any> RpcChannel.Companion.forTest(
    service: S,
    policy: RpcPolicy = RpcPolicy.Authed,
): RpcChannel<S> = RpcChannel(DirectRpcDispatch(service), policy)

/** As [forTest], but the dispatch throws [faults] in order before reaching the service. */
internal fun <S : Any> RpcChannel.Companion.forTestScripted(
    service: S,
    faults: List<Throwable>,
    policy: RpcPolicy = RpcPolicy.Authed,
): RpcChannel<S> = RpcChannel(ScriptedRpcDispatch(service, faults), policy)

/**
 * A LIVE-ENGINE test channel: the production [RpcProxyCache] over a real (test-supplied) client,
 * exposed through the channel API. Unlike [forTest] — which is a single fixed proxy with NO
 * reconnect — this drives the real bounded, single-flight, self-healing engine, so a test can prove
 * genuine transport recovery (reconnect after a dead socket, a token-refresh handshake retry, a
 * `withTimeout` bound on a hung frame) end-to-end against a live server. Reach for it only when the
 * behaviour under test IS the engine; use [forTest] for ordinary repository unit tests.
 */
internal inline fun <reified S : Any> RpcChannel.Companion.forServer(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    policy: RpcPolicy = RpcPolicy.Authed,
    authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
): RpcChannel<S> =
    RpcChannel(
        RpcProxyCache(apiClientFactory, serverConfig, authRecovery) { client, baseUrl ->
            client.rpc("$baseUrl${policy.mount}").withService<S>()
        },
        policy,
    )
