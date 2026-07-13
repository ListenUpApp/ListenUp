package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import kotlin.coroutines.cancellation.CancellationException

/**
 * Run [run] and fold its outcome into an [AppResult] at the RPC boundary.
 *
 * A business [AppResult.Failure] returned by [run] is a VALUE — it passes straight through and
 * never enters the catch clauses, so it can never be mistaken for a transport fault. Only a thrown
 * failure is translated: a post-delivery [RpcOutcomeUnknownException] folds to the honest,
 * non-retryable [TransportError.OutcomeUnknown]; a [CancellationException] re-raises (kotlinx.coroutines
 * canonical rule — the engine already converts its own request timeout to OutcomeUnknown, so a
 * cancellation reaching here is a genuine structured-concurrency cancel, never a swallowable fault);
 * and every other throwable maps once through [ErrorMapper].
 *
 * This is the single fold boundary: [RpcChannel.call] wraps [RpcProxyCache.call] with it in
 * production, and engine-level tests call it directly around a bare [RpcProxyCache.call], so both
 * exercise the real boundary rather than a re-implementation of it.
 */
internal suspend fun <T> catchingRpcResult(run: suspend () -> AppResult<T>): AppResult<T> =
    try {
        run()
    } catch (e: RpcOutcomeUnknownException) {
        // The frame was sent but the response was lost — the mutation may have committed. Surface it as
        // the honest, NON-retryable OutcomeUnknown (not a re-raised cancellation, and not a retryable
        // Timeout that would license a blind re-fire — double-applying a non-idempotent mutation).
        // Route the wrapped cause (the real per-instance diagnostic) into debugInfo; the exception's own
        // message is a fixed constant, so it would carry no diagnostic value.
        AppResult.Failure(TransportError.OutcomeUnknown(debugInfo = e.cause?.toString() ?: e.message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(ErrorMapper.map(e))
    }
