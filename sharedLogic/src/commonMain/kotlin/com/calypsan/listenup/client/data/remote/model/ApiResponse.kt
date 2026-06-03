package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.AppResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Expected envelope version from the server.
 * If this doesn't match, client and server are out of sync.
 */
private const val EXPECTED_ENVELOPE_VERSION = 1

/**
 * Standard API response wrapper from the ListenUp server.
 *
 * All API endpoints return responses in one of two envelope formats:
 *
 * Success: { "v": 1, "success": true, "data": {...} }
 * Simple error: { "v": 1, "success": false, "error": "..." }
 * Detailed error: { "v": 1, "code": "...", "message": "...", "details": {...} }
 *
 * The "v" field is a canary - if it's missing or wrong, we know the
 * response structure doesn't match expectations and should fail loudly
 * rather than silently producing garbage data.
 */
@Serializable
data class ApiResponse<T>(
    @SerialName("v")
    val version: Int? = null,
    @SerialName("success")
    val success: Boolean = false,
    @SerialName("data")
    val data: T? = null,
    @SerialName("error")
    val error: String? = null,
    @SerialName("code")
    val code: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("details")
    val details: JsonElement? = null,
) {
    /**
     * Convert this API response to our domain Result type.
     *
     * This transformation happens at the data layer boundary, keeping
     * our domain layer free of API-specific concerns.
     *
     * @throws EnvelopeMismatchException if the response structure is invalid
     */
    fun toResult(): AppResult<T> {
        // FIRST: Validate envelope structure (canary check)
        if (version == null) {
            throw EnvelopeMismatchException(
                "Response missing 'v' field. Response structure mismatch. " +
                    "Expected: {v: 1, success: bool, data: T} or {v: 1, code: string, message: string}",
            )
        }

        if (version != EXPECTED_ENVELOPE_VERSION) {
            throw EnvelopeMismatchException(
                "Envelope version mismatch. Expected v=$EXPECTED_ENVELOPE_VERSION, got v=$version. " +
                    "Client may need update.",
            )
        }

        // Handle detailed error envelope format (has code/message instead of success/error)
        if (code != null) {
            return Failure(ApiException(code = code, message = message ?: "Unknown error"))
        }

        // Handle standard envelope format
        return if (success && data != null) {
            AppResult.Success(data)
        } else if (success && data == null) {
            // Success with null data is valid for some operations (DELETE, etc.)
            // The caller must handle T being nullable
            @Suppress("UNCHECKED_CAST")
            AppResult.Success(null as T)
        } else {
            Failure(ApiException(message = error ?: "Unknown API error"))
        }
    }
}

/**
 * Exception thrown when the API response envelope structure is invalid.
 *
 * This indicates a client/server contract violation, not a business error.
 * It means the response JSON parsed successfully but doesn't match the
 * expected envelope structure (e.g., missing version field).
 */
class EnvelopeMismatchException(
    message: String,
) : Exception(message)

/**
 * Exception thrown when an API call fails.
 *
 * This wraps server-provided error messages in a typed exception
 * that can be handled appropriately by the presentation layer.
 *
 * @param code Optional error code for programmatic handling (e.g., "conflict", "validation_error")
 * @param message Human-readable error message
 */
class ApiException(
    val code: String? = null,
    message: String,
) : Exception(message)
