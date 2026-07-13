package com.calypsan.listenup.api.result

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError

// ---- Construction sugar ------------------------------------------------------------------

/** Sugar for `AppResult.Success(value)`. */
fun <T> success(value: T): AppResult<T> = AppResult.Success(value)

/** Sugar for `AppResult.Failure(error)`. */
fun failure(error: AppError): AppResult<Nothing> = AppResult.Failure(error)

// ---- Typed-failure constructors ----------------------------------------------------------

/** Construct an [AppResult.Failure] carrying a generic [ValidationError] so [AppResult.Failure.message] survives. */
fun failureOf(
    message: String,
    debugInfo: String? = null,
): AppResult.Failure = AppResult.Failure(ValidationError(message = message, debugInfo = debugInfo))

/**
 * Construct an [AppResult.Failure] carrying a [ValidationError] for validation failures.
 *
 * [field] names the input the error refers to (see [ValidationError.field]) so callers can
 * highlight the right form field without substring-matching the message.
 */
fun validationError(
    message: String,
    field: String? = null,
): AppResult.Failure = AppResult.Failure(ValidationError(message = message, field = field))

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
    AppResult.Failure(TransportError.Server5xx(debugInfo = cause?.message ?: message, statusCode = 0))
