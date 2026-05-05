package com.calypsan.listenup.client.core.error

/**
 * Base sealed interface for all application errors.
 *
 * Provides a reusable, cross-platform error handling framework with:
 * - User-facing messages
 * - Error codes for logging/analytics
 * - Retry capability indication
 * - Debug information for troubleshooting
 *
 * This foundation allows domain-specific errors to extend and customize
 * while maintaining consistent error handling patterns across the app.
 *
 * Usage:
 * ```kotlin
 * sealed interface MyFeatureError : AppError {
 *     data class SpecificError(...) : MyFeatureError {
 *         override val message = "User-friendly message"
 *         override val code = "SPECIFIC_ERROR"
 *         override val isRetryable = false
 *         override val debugInfo = null
 *     }
 * }
 * ```
 */
@Deprecated(
    message = "Legacy error hierarchy. Migrate to `com.calypsan.listenup.api.error.AppError`. " +
        "This entire package is deleted in Task 16 of Phase 3.",
    level = DeprecationLevel.WARNING,
)
sealed interface AppError {
    /**
     * User-facing error message.
     * Should be clear, actionable, and non-technical.
     */
    val message: String

    /**
     * Error code for logging and analytics.
     * Format: CATEGORY_DETAIL (e.g., "NETWORK_TIMEOUT", "SERVER_ERROR_404")
     */
    val code: String

    /**
     * Whether this error can be retried.
     * Used to show/hide retry buttons in UI.
     */
    val isRetryable: Boolean

    /**
     * Technical debug information.
     * Null in production, populated in debug builds for troubleshooting.
     */
    val debugInfo: String?
}

/**
 * Network connectivity errors (DNS, timeout, no internet).
 *
 * Examples: No internet, DNS failure, connection timeout, SSL errors.
 */
data class NetworkError(
    override val message: String = "No internet connection. Check your network.",
    override val debugInfo: String? = null,
) : AppError {
    override val code = "NETWORK_ERROR"
    override val isRetryable = true
}

/**
 * Server returned error response (4xx, 5xx).
 *
 * HTTP status codes mapped to user-friendly messages.
 */
data class ServerError(
    val statusCode: Int,
    override val message: String = "Server error. Please try again later.",
    override val debugInfo: String? = null,
) : AppError {
    override val code = "SERVER_ERROR_$statusCode"
    override val isRetryable = statusCode in 500..599 // Server errors are retryable
}

/**
 * Invalid data format (serialization, validation).
 *
 * Examples: JSON parsing errors, schema mismatches, unexpected response format.
 */
data class DataError(
    override val message: String,
    override val debugInfo: String? = null,
) : AppError {
    override val code = "DATA_ERROR"
    override val isRetryable = false
}

/**
 * Authentication/authorization errors.
 *
 * Examples: Invalid credentials, expired tokens, missing permissions.
 */
data class AuthError(
    override val message: String = "Authentication failed. Please log in again.",
    override val debugInfo: String? = null,
) : AppError {
    override val code = "AUTH_ERROR"
    override val isRetryable = false
}

/**
 * Unknown/unexpected errors.
 *
 * Fallback for exceptions that don't map to specific error types.
 */
data class UnknownError(
    override val message: String = "An unexpected error occurred.",
    override val debugInfo: String?,
) : AppError {
    override val code = "UNKNOWN_ERROR"
    override val isRetryable = true
}
