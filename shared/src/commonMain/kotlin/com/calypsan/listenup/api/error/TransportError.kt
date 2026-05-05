package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport-layer failures: connectivity, HTTP status, malformed responses.
 *
 * Replaces the legacy `client.core.error.NetworkError`, `ServerError`, and
 * `DataError` types. Tasks 11-13 in this PR migrate consumers to use this
 * subtree; Task 16 deletes the legacy hierarchy.
 *
 * The wire never carries a [TransportError] (it's a client-local mapping of
 * transport-layer exceptions), so every subtype has `correlationId = null`
 * by construction in the common case.
 */
@Serializable
sealed interface TransportError : AppError {

    /** Network is unavailable: DNS failure, no internet, connection refused. */
    @Serializable
    @SerialName("TransportError.NetworkUnavailable")
    data class NetworkUnavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TransportError {
        override val message: String = "No internet connection. Check your network."
        override val code: String = "TRANSPORT_NETWORK_UNAVAILABLE"
        override val isRetryable: Boolean = true
    }

    /** Network operation timed out (connect, read, or request). */
    @Serializable
    @SerialName("TransportError.Timeout")
    data class Timeout(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TransportError {
        override val message: String = "Connection timed out. Please try again."
        override val code: String = "TRANSPORT_TIMEOUT"
        override val isRetryable: Boolean = true
    }

    /** Server returned a 4xx response that doesn't map to a typed domain error. */
    @Serializable
    @SerialName("TransportError.Server4xx")
    data class Server4xx(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val statusCode: Int,
    ) : TransportError {
        override val message: String = "Request rejected by server (HTTP $statusCode)."
        override val code: String = "TRANSPORT_SERVER_4XX"
        override val isRetryable: Boolean = false
    }

    /** Server returned a 5xx response. */
    @Serializable
    @SerialName("TransportError.Server5xx")
    data class Server5xx(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val statusCode: Int,
    ) : TransportError {
        override val message: String = "Server error (HTTP $statusCode). Please try again."
        override val code: String = "TRANSPORT_SERVER_5XX"
        override val isRetryable: Boolean = true
    }

    /** Response body could not be deserialized (JSON parse error, schema mismatch). */
    @Serializable
    @SerialName("TransportError.DataMalformed")
    data class DataMalformed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val detail: String,
    ) : TransportError {
        override val message: String = "Server response was malformed."
        override val code: String = "TRANSPORT_DATA_MALFORMED"
        override val isRetryable: Boolean = false
    }
}
