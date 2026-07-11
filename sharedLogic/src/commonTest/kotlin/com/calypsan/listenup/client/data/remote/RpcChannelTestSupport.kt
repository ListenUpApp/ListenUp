package com.calypsan.listenup.client.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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
    ): R = withTimeout(timeout) { block(service) }

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
        return withTimeout(timeout) { block(service) }
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
