package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Run [run] and fold its outcome into an [AppResult] at the RPC boundary.
 *
 * A business [AppResult.Failure] returned by [run] is a VALUE — it passes straight through and
 * never enters the catch clauses, so it can never be mistaken for a transport fault. Only a thrown
 * failure is translated: our [TransportError.Timeout] bound trips to a typed timeout, a genuine
 * caller [CancellationException] re-raises (kotlinx.coroutines canonical rule), and every other
 * throwable maps once through [ErrorMapper].
 *
 * Shared by [RpcProxyCache.rpcCall] (production) and the test RPC-factory doubles, so repository
 * unit tests exercise the real boundary rather than a re-implementation of it.
 */
internal suspend fun <T> catchingRpcResult(run: suspend () -> AppResult<T>): AppResult<T> =
    try {
        run()
    } catch (e: TimeoutCancellationException) {
        AppResult.Failure(TransportError.Timeout(debugInfo = e.message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(ErrorMapper.map(e))
    }

/**
 * Run [block] against the cached service proxy through the bounded, self-healing [RpcProxyCache.call]
 * engine, folding the outcome into an [AppResult].
 *
 * This is the single repository-facing RPC shape: transport deaths heal invisibly (one bounded
 * reconnect + retry), a surviving fault surfaces as a typed [AppResult.Failure], and a business
 * failure returned by the service passes through untouched — a `ValidationError` never triggers a
 * reconnect.
 */
internal suspend fun <S : Any, T> RpcProxyCache<S>.rpcCall(block: suspend (S) -> AppResult<T>): AppResult<T> =
    catchingRpcResult { call { block(it) } }
