package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.AppException
import com.calypsan.listenup.client.core.flatMap
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiException
import com.calypsan.listenup.client.data.remote.model.ApiResponse

// ---- Legacy throwing helpers (deleted in slice 18) ------------------------------------

/**
 * Extract data from [ApiResponse] or throw. Consolidates the:
 * ```
 * val r = client.get(url).body<ApiResponse<T>>()
 * if (!r.success || r.data == null) throw SomeException(r.error ?: "Failed")
 * return r.data
 * ```
 * boilerplate at the data-layer boundary.
 *
 * Throwing convention is being unified on [com.calypsan.listenup.client.core.AppResult]
 * by Task 27d. Until that lands, this helper bridges envelope failures into the throwable
 * channel via [AppException] (which now carries the unified
 * [com.calypsan.listenup.api.error.AppError] directly — no hierarchy bridge needed).
 *
 * @throws AppException when the envelope indicates failure — carrying the typed error
 *   so callers can react to it.
 * @throws ApiException when the success envelope arrived with null data.
 */
fun <T> ApiResponse<T>.dataOrThrow(errorMessage: String): T =
    when (val result = toResult()) {
        is Success -> result.data ?: throw ApiException(message = errorMessage)
        is Failure -> throw AppException(result.error)
    }

/**
 * Extract data from [ApiResponse] or throw a caller-supplied exception. Used by call sites
 * that want to map the generic envelope failure to a feature-specific exception type.
 */
inline fun <T, E : Exception> ApiResponse<T>.dataOrThrow(exceptionFactory: (String) -> E): T =
    when (val result = toResult()) {
        is Success -> result.data ?: throw exceptionFactory("Response data was null")
        is Failure -> throw exceptionFactory(result.error.message)
    }

/**
 * Validate an [ApiResponse] without extracting a body — for POST/DELETE operations that
 * return no data. Throws [AppException] on failure carrying the envelope's typed error.
 */
fun <T> ApiResponse<T>.validateOrThrow() {
    when (val result = toResult()) {
        is Success -> { /* no-op */ }

        is Failure -> {
            throw AppException(result.error)
        }
    }
}

// ---- New AppResult-returning helpers --------------------------------------------------

/**
 * Canonical API method shape. Wraps a Ktor request that produces an [ApiResponse]
 * envelope and returns an [AppResult]. `suspendRunCatching` catches Ktor's typed
 * exceptions (`ResponseException`, `IOException`, etc.) — which the
 * [HttpClientErrorHandling] plugin's `expectSuccess = true` setting raises on
 * non-2xx — and routes them through [com.calypsan.listenup.client.core.error.ErrorMapper].
 *
 * @param errorMessage Used as the `TransportError.DataMalformed.detail` if the
 *   envelope reports `success = true` but `data == null` (the only case where
 *   the body decoder couldn't surface a typed error itself).
 */
suspend inline fun <T> apiCall(
    errorMessage: String,
    crossinline block: suspend () -> ApiResponse<T>,
): AppResult<T> = suspendRunCatching { block() }.flatMap { it.dataOrFailure(errorMessage) }

/**
 * Unit-returning variant of [apiCall] for POST/DELETE endpoints whose response
 * envelope carries no body data.
 */
suspend inline fun apiCallUnit(
    crossinline block: suspend () -> ApiResponse<*>,
): AppResult<Unit> = suspendRunCatching { block() }.flatMap { it.validateOrFailure() }

/**
 * Extract data from [ApiResponse] as an [AppResult]. Used internally by [apiCall];
 * exposed for the rare call-site that has the [ApiResponse] in hand and just wants
 * the typed result.
 */
fun <T> ApiResponse<T>.dataOrFailure(errorMessage: String): AppResult<T> =
    when (val result = toResult()) {
        is Success -> result.data?.let { AppResult.Success(it) }
            ?: AppResult.Failure(TransportError.DataMalformed(detail = errorMessage))
        is Failure -> result
    }

/**
 * Validate an [ApiResponse] without extracting a body — for POST/DELETE
 * endpoints. Returns [AppResult.Success]`(Unit)` on success, [AppResult.Failure]
 * carrying the envelope's typed error otherwise.
 */
fun <T> ApiResponse<T>.validateOrFailure(): AppResult<Unit> =
    when (val result = toResult()) {
        is Success -> AppResult.Success(Unit)
        is Failure -> result
    }
