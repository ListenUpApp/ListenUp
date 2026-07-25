package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import kotlin.coroutines.cancellation.CancellationException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import com.calypsan.listenup.api.error.AppError

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

/**
 * Carries an already-typed [AppError] out of a [pullCatching] block.
 *
 * Needed because a nested RPC call already yields an [AppResult]; without this the typed
 * failure would have to be re-derived from a throwable by [ErrorMapper], collapsing a precise
 * business error (an unknown sync domain, say) into a generic transport one.
 */
internal class TypedAppErrorException(
    val error: AppError,
) : Exception(error.message)

/**
 * Like [suspendRunCatching], but preserves an [AppError] thrown as [TypedAppErrorException]
 * instead of re-mapping it.
 *
 * Use where a block mixes plain throwing work (database writes) with calls that already return
 * [AppResult]: unwrap those with `getOrElse { throw TypedAppErrorException(it) }` and the typed
 * error survives to the caller intact.
 */
internal suspend inline fun <T> pullCatching(crossinline block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TypedAppErrorException) {
        AppResult.Failure(e.error)
    } catch (e: Exception) {
        Failure(e)
    }
