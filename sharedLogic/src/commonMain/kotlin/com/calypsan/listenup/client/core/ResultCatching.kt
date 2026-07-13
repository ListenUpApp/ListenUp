package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import kotlin.coroutines.cancellation.CancellationException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Map a throwable to a typed [AppResult.Failure] via [ErrorMapper]. */
fun Failure(throwable: Throwable): AppResult.Failure = AppResult.Failure(ErrorMapper.map(throwable))

/**
 * Catch exceptions in a suspend block and wrap them in [AppResult]. Re-throws
 * [CancellationException] to preserve coroutine cancellation; routes all other
 * throwables through [ErrorMapper] via [Failure].
 */
@OptIn(ExperimentalContracts::class)
suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): AppResult<T> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    return try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Failure(e)
    }
}
