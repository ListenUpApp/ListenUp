package com.calypsan.listenup.client.core.error

/**
 * Domain-specific errors for server connection flow.
 *
 * Extends base AppError with context-specific messages that guide
 * users through the server setup process. These errors provide
 * actionable feedback for common issues like invalid URLs or
 * unreachable servers.
 *
 * Design pattern: Domain errors extend AppError to add context-specific
 * information while maintaining the framework's benefits (error codes,
 * retry capability, debug info).
 */
@Deprecated(
    message = "Migrate to `com.calypsan.listenup.api.error.ServerConnectError`. Deleted in Task 16 of Phase 3.",
    level = DeprecationLevel.WARNING,
)
sealed interface ServerConnectError : AppError {
    /**
     * User entered an invalid URL format.
     *
     * Provides specific guidance based on the validation failure reason:
     * - "blank": No URL entered
     * - "localhost_physical": Localhost on physical device (won't work)
     * - "malformed": Syntactically invalid URL
     *
     * Note: Protocol (http:// or https://) is added automatically if missing.
     *
     * @property reason Validation failure category for error code
     */
    data class InvalidUrl(
        val reason: String,
    ) : ServerConnectError {
        override val message =
            when (reason) {
                "blank" -> "Please enter a server URL"
                "localhost_physical" -> "Localhost URLs only work on emulator. Use your computer's IP address."
                "malformed" -> "Invalid URL format"
                else -> "Invalid URL: $reason"
            }
        override val code = "INVALID_URL_${reason.uppercase()}"
        override val isRetryable = false
        override val debugInfo = null
    }

    /**
     * Server responded but doesn't appear to be a ListenUp server.
     *
     * This error occurs when:
     * - Server returns non-ListenUp response format
     * - /api/v1/instance endpoint doesn't exist (404)
     * - Response schema doesn't match expected Instance format
     *
     * @property debugInfo Technical details about what went wrong
     */
    data class NotListenUpServer(
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message = "This doesn't appear to be a ListenUp server."
        override val code = "NOT_LISTENUP_SERVER"
        override val isRetryable = false
    }

    /**
     * Server is not reachable at the given URL.
     *
     * This error occurs when:
     * - Connection is refused (server not running)
     * - Network timeout
     * - Host not found
     *
     * @property debugInfo Technical details about connection failure
     */
    data class ServerNotReachable(
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message = "Cannot reach server. Check that it's running and the URL is correct."
        override val code = "SERVER_NOT_REACHABLE"
        override val isRetryable = true
    }

    /**
     * Server verification failed due to generic error.
     *
     * Wraps base AppError types (NetworkError, ServerError, etc.) with
     * server-connect context. This provides a single error type for the UI
     * while preserving the original error's characteristics.
     *
     * @property cause The underlying AppError (network, server, etc.)
     */
    data class VerificationFailed(
        val cause: AppError,
    ) : ServerConnectError {
        override val message = cause.message
        override val code = "VERIFICATION_${cause.code}"
        override val isRetryable = cause.isRetryable
        override val debugInfo = cause.debugInfo
    }
}
