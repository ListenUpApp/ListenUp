package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport-layer failures: connectivity, HTTP status, malformed responses.
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The request frame was delivered but its outcome was never observed — the connection dropped
     * while awaiting the response, so the server may have committed the operation. Never blindly
     * retryable: re-firing a non-idempotent mutation could double-apply it. Consumers that KNOW their
     * operation is idempotent (the outbox's declared-idempotent channels) may re-classify at their
     * own policy boundary.
     */
    @Serializable
    @SerialName("TransportError.OutcomeUnknown")
    data class OutcomeUnknown(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TransportError {
        override val message: String = "The request may not have completed. Check before retrying."
        override val code: String = "TRANSPORT_OUTCOME_UNKNOWN"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * Server returned a 4xx response that doesn't map to a typed domain error.
     *
     * Typed cases (401/403 auth, 429 rate-limit) should already be unwrapped by
     * the RPC interceptor into `AuthError` subtypes before reaching this catch-all.
     * Consumers may still pattern-match on [statusCode] for any special-case 4xx
     * handling not covered by the typed `AuthError` hierarchy.
     */
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * Response body could not be deserialized (JSON parse error, schema mismatch).
     *
     * [detail] is a concise diagnostic always intended for surfacing alongside
     * the user-facing [message] (e.g., `"JSON parse failed at offset 42"`),
     * separate from [debugInfo] which carries debug-only stacktraces or
     * internal context not safe for production UI.
     */
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * A well-formed 2xx response whose body this client cannot parse (envelope-version skew or a
     * shape mismatch) — evidence the app and server contract versions have diverged. NON-BLOCKING:
     * surfaced as the "Update available" hint, never as an auth failure. [detail] is the technical
     * per-instance context (not user-facing).
     */
    @Serializable
    @SerialName("TransportError.ContractMismatch")
    data class ContractMismatch(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val detail: String,
    ) : TransportError {
        override val message: String = "The app and server versions don't match."
        override val code: String = "TRANSPORT_CONTRACT_MISMATCH"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * A save went through but the new cover or photo did not reach the server, and the server will not
     * take it as it is (a refusal, not a bad connection): nothing is queued, so re-saving the image
     * is the way to try again. Client-local, raised by an edit screen's save outcome, never by the
     * wire. The cause's own code and detail ride in [debugInfo].
     */
    @Serializable
    @SerialName("TransportError.ImageNotUploaded")
    data class ImageNotUploaded(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TransportError {
        override val message: String = "Saved. The new image couldn't be uploaded."
        override val code: String = "TRANSPORT_IMAGE_NOT_UPLOADED"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * A save went through and the new cover or photo is on this device, but the server could not be
     * reached; the upload is queued and goes up by itself once the server answers. Client-local, raised
     * by an edit screen's save outcome, never by the wire. The cause's own code and detail ride in
     * [debugInfo].
     */
    @Serializable
    @SerialName("TransportError.ImageUploadQueued")
    data class ImageUploadQueued(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TransportError {
        override val message: String = "Saved. The new image will upload when the server is reachable."
        override val code: String = "TRANSPORT_IMAGE_UPLOAD_QUEUED"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }
}
