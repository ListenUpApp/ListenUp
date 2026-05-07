package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.flatMap
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiResponse

// ---- AppResult-returning helpers -----------------------------------------------------

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
