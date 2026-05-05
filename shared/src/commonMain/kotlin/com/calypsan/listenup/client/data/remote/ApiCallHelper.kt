package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.toLegacy
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.AppException
import com.calypsan.listenup.client.data.remote.model.ApiException
import com.calypsan.listenup.client.data.remote.model.ApiResponse

/**
 * Extract data from [ApiResponse] or throw. Consolidates the:
 * ```
 * val r = client.get(url).body<ApiResponse<T>>()
 * if (!r.success || r.data == null) throw SomeException(r.error ?: "Failed")
 * return r.data
 * ```
 * boilerplate at the data-layer boundary.
 *
 * The envelope's typed error is a unified [com.calypsan.listenup.api.error.AppError];
 * the legacy [AppException] (still in use until Task 16) requires the legacy hierarchy,
 * so we bridge via `toLegacy()` at the throw site. Tasks 13 and 16 finish the migration:
 * Task 13 reshapes [AppException] / `ErrorMapper` to consume unified errors directly;
 * Task 16 deletes the legacy hierarchy and removes the bridge.
 *
 * @throws AppException when the envelope indicates failure — carrying the typed error
 *   (currently bridged into the legacy hierarchy) so callers can react to it.
 * @throws ApiException when the success envelope arrived with null data.
 */
fun <T> ApiResponse<T>.dataOrThrow(errorMessage: String): T =
    when (val result = toResult()) {
        is Success -> result.data ?: throw ApiException(message = errorMessage)
        is Failure -> throw AppException(result.error.toLegacy())
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
            throw AppException(result.error.toLegacy())
        }
    }
}
