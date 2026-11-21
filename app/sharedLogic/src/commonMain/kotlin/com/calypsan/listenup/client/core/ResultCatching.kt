package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.ktor.client.plugins.ResponseException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import com.calypsan.listenup.api.error.AppError

/** Map a throwable to a typed [AppResult.Failure] via [ErrorMapper]. */
fun Failure(throwable: Throwable): AppResult.Failure = AppResult.Failure(ErrorMapper.map(throwable))

/**
 * Catch throwables in a suspend block and wrap them in [AppResult]. Re-throws
 * [CancellationException] to preserve coroutine cancellation; routes all other
 * throwables through [ErrorMapper] via [restFailure].
 *
 * Catches [Throwable], not [Exception], matching the RPC boundary (`catchingRpcResult`): in the
 * browser a native error such as `RangeError` is a [Throwable] but not an [Exception].
 */
@OptIn(ExperimentalContracts::class)
suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): AppResult<T> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    return try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        restFailure(e)
    }
}

/**
 * The failure for a throwable caught at the REST boundary. A refusal the server explained (Ktor's
 * [ResponseException] with a typed error in its body) is that error; everything else maps as [Failure].
 */
@PublishedApi
internal suspend fun restFailure(throwable: Throwable): AppResult.Failure =
    if (throwable is ResponseException) {
        AppResult.Failure(ErrorMapper.forResponse(throwable))
    } else {
        Failure(throwable)
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
    cause: Throwable? = null,
) : Exception(error.message, cause)

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
    } catch (e: Throwable) {
        Failure(e)
    }
