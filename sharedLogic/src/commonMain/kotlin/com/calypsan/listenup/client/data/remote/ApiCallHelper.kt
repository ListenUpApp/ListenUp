package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.core.appJson
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException

// ---- AppResult-returning helpers -----------------------------------------------------

/**
 * Canonical API method shape. Wraps a Ktor request that produces an [ApiResponse]
 * envelope and returns an [AppResult].
 *
 * The whole pipeline — the request AND the [ApiResponse.toResult] envelope fold — runs
 * inside one catch boundary, so a well-formed 2xx whose envelope this client can't parse
 * (the [com.calypsan.listenup.client.data.remote.model.EnvelopeMismatchException] raised by
 * the canary check) is turned into a typed [AppResult.Failure], never an exception escaping
 * past the AppResult contract. Ktor's `expectSuccess = true` ([HttpClientErrorHandling])
 * raises `ResponseException` on non-2xx; that too is caught here and routed through
 * [mapThrowableToAppError].
 *
 * @param errorMessage Used as the `TransportError.DataMalformed.detail` if the
 *   envelope reports `success = true` but `data == null` (the only case where
 *   the body decoder couldn't surface a typed error itself).
 */
suspend inline fun <T> apiCall(
    errorMessage: String,
    crossinline block: suspend () -> ApiResponse<T>,
): AppResult<T> =
    try {
        block().dataOrFailure(errorMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Failure(mapThrowableToAppError(e))
    }

/**
 * Unit-returning variant of [apiCall] for POST/DELETE endpoints whose response
 * envelope carries no body data.
 */
suspend inline fun apiCallUnit(crossinline block: suspend () -> ApiResponse<*>): AppResult<Unit> =
    try {
        block().validateOrFailure()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Failure(mapThrowableToAppError(e))
    }

/**
 * Extract data from [ApiResponse] as an [AppResult]. Used internally by [apiCall];
 * exposed for the rare call-site that has the [ApiResponse] in hand and just wants
 * the typed result.
 */
fun <T> ApiResponse<T>.dataOrFailure(errorMessage: String): AppResult<T> =
    when (val result = toResult()) {
        is AppResult.Success -> {
            result.data?.let { AppResult.Success(it) }
                ?: AppResult.Failure(TransportError.DataMalformed(detail = errorMessage))
        }

        is AppResult.Failure -> {
            result
        }
    }

/**
 * Validate an [ApiResponse] without extracting a body — for POST/DELETE
 * endpoints. Returns [AppResult.Success]`(Unit)` on success, [AppResult.Failure]
 * carrying the envelope's typed error otherwise.
 */
fun <T> ApiResponse<T>.validateOrFailure(): AppResult<Unit> =
    when (val result = toResult()) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> result
    }

/**
 * Translate a caught transport throwable into a typed [AppError].
 *
 * For a non-2xx [ResponseException], first attempts to decode the server's REST error body as a
 * typed [AppError] — preserving its `correlationId`, `isRetryable`, and per-instance fields
 * (e.g. `AuthError.RateLimited.retryAfterSeconds`) rather than flattening it to a status-only
 * `Server4xx`/`Server5xx`. Falls back to status-based [ErrorMapper] mapping when the body is
 * absent, unreadable, or not an [AppError] (e.g. a plain-text 404 page). Every other throwable —
 * including the envelope-canary `EnvelopeMismatchException` — routes straight through [ErrorMapper].
 */
@PublishedApi
internal suspend fun mapThrowableToAppError(throwable: Throwable): AppError {
    if (throwable is ResponseException) {
        decodeAppErrorBody(throwable)?.let { return it }
    }
    return ErrorMapper.map(throwable)
}

/**
 * Reads a [ResponseException]'s body and decodes it as a typed [AppError], or `null` if the body
 * can't be read or isn't a recognised error shape. Reading a response body is suspend and can itself
 * fail (already consumed, connection dropped) — guarded so a read failure degrades to status-based
 * mapping rather than masking the original error.
 */
private suspend fun decodeAppErrorBody(exception: ResponseException): AppError? {
    val body =
        try {
            exception.response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            return null
        }
    return decodeTypedAppError(body)
}

/**
 * Decodes a REST error body into a typed [AppError], covering both server wire shapes:
 * an escaped-exception 500 serialises a bare [AppError] (`{"type":"AppError.InternalError",…}`),
 * while a domain failure serialises an [AppResult.Failure] envelope (`{"type":"Failure","error":…}`).
 * Returns `null` for anything else so the caller can fall back to status-based mapping.
 */
private fun decodeTypedAppError(body: String): AppError? {
    if (body.isBlank()) return null
    decodeOrNull { appJson.decodeFromString<AppError>(body) }?.let { return it }
    decodeOrNull { appJson.decodeFromString<AppResult<JsonElement>>(body) }
        ?.let { if (it is AppResult.Failure) return it.error }
    return null
}

private inline fun <T> decodeOrNull(decode: () -> T): T? =
    try {
        decode()
    } catch (ignored: SerializationException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }
