package com.calypsan.listenup.api.result

import com.calypsan.listenup.api.error.AppError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * The canonical wire-level result type for fallible operations crossing the
 * server/client boundary. Returned by every fallible method in `AuthServicePublic`
 * and `AuthServiceAuthed`; carried by REST responses; consumed by client
 * repositories.
 *
 * Two variants:
 * - [Success] — carries the produced value. Wire shape: `{"type":"Success","data":...}`.
 * - [Failure] — carries an [AppError] (the typed contract error hierarchy).
 *   Wire shape: `{"type":"Failure","error":{"type":"...","correlationId":"..."}}`.
 *
 * **Why a value, not an exception:** kotlinx.rpc 0.10.x serializes exceptions
 * lossily (class name + message + stack), so typed `AuthError` payloads can't
 * survive an RPC throw. Returning [AppResult] keeps the typed error in-band as
 * structured data — server handlers fail loudly only for genuine bugs, every
 * domain failure is a value the caller exhaustively folds. This shape also
 * removes the need for a server-side `AuthException` wrapper.
 *
 * **Cancellation:** [AppResult] does not represent cancellation — that's not a
 * domain concern. Boundary helpers ([catchingResult] and friends) re-raise
 * `CancellationException` per the kotlinx.coroutines canonical rule.
 *
 * Failure constructors ([success], [failure], [failureOf], [validationError], …)
 * live in `AppResultFactories.kt`.
 *
 * **Enforcement:** an ignored `AppResult` is a swallowed error. The unused-return-value checker
 * enforces this at the *declaring scopes* (the `domain/repository` interfaces carry
 * `@file:MustUseReturnValues`; see `AppResultSurfaceIsMustUseRule`) — annotating this type itself
 * would have no effect, since the checker keys off the scope a function is declared in, not its
 * return type.
 */
@Serializable
sealed interface AppResult<out T> {
    /** Carries the value [data] produced by a successful operation. */
    @Serializable
    @SerialName("Success")
    data class Success<T>(
        val data: T,
    ) : AppResult<T>

    /** Carries the typed [error] from a failed operation; consumers fold the [AppError] hierarchy. */
    @Serializable
    @SerialName("Failure")
    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing> {
        /** Shortcut for [error].message so terse catch sites read naturally. Computed — not a wire field. */
        val message: String get() = error.message
    }
}

// ---- Smart-cast predicates ---------------------------------------------------------------

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isSuccess(): Boolean {
    contract { returns(true) implies (this@isSuccess is AppResult.Success<T>) }
    return this is AppResult.Success
}

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isFailure(): Boolean {
    contract { returns(true) implies (this@isFailure is AppResult.Failure) }
    return this is AppResult.Failure
}

// ---- Unwrap helpers ----------------------------------------------------------------------

fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> null
    }

inline fun <T> AppResult<T>.getOrElse(onFailure: (AppError) -> T): T =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> onFailure(error)
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

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(data)
        is AppResult.Failure -> this
    }

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

// ---- Additional combinators --------------------------------------------------------------

inline fun <T> AppResult<T>.getOrDefault(defaultValue: () -> T): T =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> defaultValue()
    }

suspend inline fun <T, R> AppResult<T>.mapSuspend(crossinline transform: suspend (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Failure -> this
    }

/** Collapse a nested [AppResult] into a single layer. */
fun <T> AppResult<AppResult<T>>.flatten(): AppResult<T> = flatMap { it }

inline fun <T> AppResult<T>.recover(recovery: (AppError) -> T): AppResult<T> =
    when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> AppResult.Success(recovery(error))
    }
