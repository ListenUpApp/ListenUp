package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from external metadata lookups (Audible catalog API,
 * iTunes Search API, or any future external enrichment source).
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [AppResult.Failure]. [correlationId] links the server log line to the
 * client's error display. [debugInfo] carries per-instance technical detail
 * (e.g. the HTTP status code) for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is a strict middleware contract:
 * - `true` → retry middleware can blindly re-fire without user action
 *   (rate-limit backoff, transient 5xx).
 * - `false` → user or operator must act (bad query, permanent unavailability).
 */
@Serializable
sealed interface MetadataError : AppError {
    /**
     * The external API responded with HTTP 429 (Too Many Requests).
     * Backing off and retrying is safe and expected.
     */
    @Serializable
    @SerialName("MetadataError.ExternalRateLimited")
    data class ExternalRateLimited(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MetadataError {
        override val message: String =
            "External metadata service rate-limited the request. Try again shortly."
        override val code: String = "METADATA_RATE_LIMITED"
        override val isRetryable: Boolean = true
    }

    /**
     * The external API returned a 5xx error or the network connection failed.
     * The service is temporarily unreachable; a later retry is reasonable.
     */
    @Serializable
    @SerialName("MetadataError.ExternalUnavailable")
    data class ExternalUnavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MetadataError {
        override val message: String =
            "Couldn't reach the external metadata service. Try again later."
        override val code: String = "METADATA_UNAVAILABLE"
        override val isRetryable: Boolean = true
    }

    /**
     * The external API returned HTTP 404 or an empty result set for the query.
     * No metadata is available for this title; retrying the same query won't help.
     */
    @Serializable
    @SerialName("MetadataError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MetadataError {
        override val message: String = "No metadata found for that query."
        override val code: String = "METADATA_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The external API returned a response that could not be parsed — unexpected
     * JSON shape, missing required fields, or a type mismatch. This typically
     * indicates an undocumented API change; retrying is unlikely to help.
     */
    @Serializable
    @SerialName("MetadataError.Malformed")
    data class Malformed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MetadataError {
        override val message: String = "The external metadata response was malformed."
        override val code: String = "METADATA_MALFORMED"
        override val isRetryable: Boolean = false
    }
}
