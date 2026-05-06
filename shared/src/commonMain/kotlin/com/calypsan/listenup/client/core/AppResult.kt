@file:Suppress("TooManyFunctions")

package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.core.error.AppException
import com.calypsan.listenup.client.core.error.ErrorMapper
import kotlin.MustUseReturnValues
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException

/**
 * The canonical result type for every fallible suspend function in the codebase.
 *
 * One sealed hierarchy, two variants:
 * - [Success] — carries the produced value.
 * - [Failure] — carries an [AppError], already categorised and user-message-ready.
 *
 * Replaces the three-way split Finding 01 D1 diagnosed (now-deleted `core.AppResult<T>` plus
 * the now-deleted `core.AsyncState<T>` plus [AppError], with no conversion path between
 * them). The name avoids shadowing [kotlin.Result], which must not be used as a public API
 * return type.
 *
 * Source: Android Architecture Guide "Define Result Class for Network Responses" + Kotlin
 * sealed-class API pattern.
 */
@MustUseReturnValues
sealed interface AppResult<out T> {
    /** Carries the value [data] produced by a successful operation. */
    data class Success<T>(
        val data: T,
    ) : AppResult<T>

    /** Carries the typed [error] from a failed operation; [message] forwards [AppError.message] for terse catch sites. */
    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing> {
        /** Shortcut for [error].message so catch sites read naturally. */
        val message: String get() = error.message
    }
}

// Ergonomic aliases so `Success(x)` / `is Failure ->` read naturally at call sites.
typealias Success<T> = AppResult.Success<T>
typealias Failure = AppResult.Failure

@PublishedApi internal const val UNKNOWN_ERROR_MESSAGE = "Unknown error"

// ---- Construction helpers ----------------------------------------------------------------

/**
 * Wraps an arbitrary [Throwable] as an [AppResult.Failure]. Preserves the typed [AppError]
 * when [throwable] is already an [AppException] (which carries it directly); otherwise
 * routes through [ErrorMapper].
 *
 * The [AppException] special case becomes redundant once Task 27d migrates the throwing
 * data-layer surface to return [AppResult] directly — at which point both [AppException]
 * and this branch are deleted.
 */
fun Failure(throwable: Throwable): AppResult.Failure =
    if (throwable is AppException) {
        AppResult.Failure(throwable.error)
    } else {
        AppResult.Failure(ErrorMapper.map(throwable))
    }

// ---- Smart-cast helpers ------------------------------------------------------------------

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isSuccess(): Boolean {
    contract {
        returns(true) implies (this@isSuccess is AppResult.Success<T>)
    }
    return this is AppResult.Success
}

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isFailure(): Boolean {
    contract {
        returns(true) implies (this@isFailure is AppResult.Failure)
    }
    return this is AppResult.Failure
}

// ---- Unwrap helpers ----------------------------------------------------------------------

fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> null
    }

inline fun <T> AppResult<T>.getOrDefault(defaultValue: () -> T): T =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> defaultValue()
    }

fun <T> AppResult<T>.errorOrNull(): AppError? =
    when (this) {
        is AppResult.Success -> null
        is AppResult.Failure -> error
    }

// ---- Combinators -------------------------------------------------------------------------

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Failure -> this
    }

suspend inline fun <T, R> AppResult<T>.mapSuspend(crossinline transform: suspend (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Failure -> this
    }

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(data)
        is AppResult.Failure -> this
    }

/** Collapse a nested [AppResult] into a single layer. */
fun <T> AppResult<AppResult<T>>.flatten(): AppResult<T> = flatMap { it }

inline fun <T, R> AppResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R,
): R =
    when (this) {
        is AppResult.Success -> onSuccess(data)
        is AppResult.Failure -> onFailure(error)
    }

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

inline fun <T> AppResult<T>.recover(recovery: (AppError) -> T): AppResult<T> =
    when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> AppResult.Success(recovery(error))
    }

// ---- Boundary catch helpers --------------------------------------------------------------

/**
 * Catch exceptions in a suspend block and wrap them in [AppResult].
 *
 * Re-throws [CancellationException] to preserve coroutine cancellation semantics
 * (Finding 01 D4 / kotlinx.coroutines canonical rule). All other throwables are mapped
 * via [Failure] — which preserves [AppException.error] when the cause is already typed.
 */
@OptIn(ExperimentalContracts::class)
suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): AppResult<T> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Failure(e)
    }
}

/**
 * Non-suspending equivalent of [suspendRunCatching]. Still routes typed [AppException]s
 * through their [AppError] unchanged.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> runCatching(block: () -> T): AppResult<T> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return try {
        AppResult.Success(block())
    } catch (e: Exception) {
        Failure(e)
    }
}

// ---- Domain-specific failure factories ---------------------------------------------------

/**
 * Construct an [AppResult.Failure] carrying a generic error.
 *
 * Uses [ValidationError] so the supplied [message] survives to consumers reading
 * `failure.message`. Unified [InternalError] has a fixed message and only carries
 * [debugInfo], which would silently drop the caller-supplied text. This is a
 * transitional shim; once Task 16 deletes the legacy hierarchy and call sites
 * migrate to typed unified errors, this generic helper goes away.
 */
fun failureOf(
    message: String,
    debugInfo: String? = null,
): AppResult.Failure = AppResult.Failure(ValidationError(message = message, debugInfo = debugInfo))

/** Construct an [AppResult.Failure] carrying a [ValidationError] for validation failures. */
fun validationError(message: String): AppResult.Failure = AppResult.Failure(ValidationError(message = message))

/** Construct an [AppResult.Failure] carrying a [ValidationError] for "resource not found". */
fun notFoundError(message: String = "Resource not found"): AppResult.Failure =
    AppResult.Failure(ValidationError(message = message))

/** Construct an [AppResult.Failure] carrying a [TransportError.NetworkUnavailable]. */
@Suppress("UnusedParameter")
fun networkError(
    message: String = "Network unavailable",
    cause: Throwable? = null,
): AppResult.Failure = AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = cause?.message ?: message))

/** Construct an [AppResult.Failure] carrying an [AuthError.SessionExpired]. */
fun unauthorizedError(message: String = "Session expired"): AppResult.Failure =
    AppResult.Failure(AuthError.SessionExpired(debugInfo = message))

/** Construct an [AppResult.Failure] carrying a [TransportError.Server5xx] with unknown status. */
@Suppress("UnusedParameter")
fun serverError(
    message: String,
    cause: Throwable? = null,
): AppResult.Failure =
    AppResult.Failure(
        TransportError.Server5xx(debugInfo = cause?.message ?: message, statusCode = 0),
    )
