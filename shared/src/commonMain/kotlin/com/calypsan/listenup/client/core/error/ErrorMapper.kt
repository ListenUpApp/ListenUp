package com.calypsan.listenup.client.core.error

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Maps exceptions to AppError types.
 *
 * Uses pure Kotlin/Ktor types for full cross-platform compatibility.
 * Works on Android, iOS, Desktop, and Web.
 *
 * This mapper handles all common network and serialization exceptions,
 * providing consistent error handling across the application.
 *
 * Usage:
 * ```kotlin
 * try {
 *     api.fetchData()
 * } catch (e: Exception) {
 *     val error = ErrorMapper.map(e)
 *     _state.update { it.copy(error = error) }
 * }
 * ```
 */
@Deprecated(
    message = "Legacy ErrorMapper produces legacy AppError. Task 13 rewrites it to produce the unified hierarchy.",
    level = DeprecationLevel.WARNING,
)
object ErrorMapper {
    /**
     * Map any exception to an AppError.
     *
     * Handles all common network/serialization exceptions with appropriate
     * user-facing messages and retry capability indication.
     *
     * @param exception The exception to map
     * @return Corresponding AppError with user-friendly message
     */
    fun map(exception: Throwable): AppError =
        when (exception) {
            // Ktor network connectivity issues (KMP)
            is ConnectTimeoutException,
            is SocketTimeoutException,
            -> {
                NetworkError(
                    message = "Connection timed out. Check your network.",
                    debugInfo = exception.message,
                )
            }

            // Ktor HTTP timeout (KMP)
            is HttpRequestTimeoutException -> {
                NetworkError(
                    message = "Request timed out. Please try again.",
                    debugInfo = exception.message,
                )
            }

            // Ktor HTTP errors (KMP)
            is ResponseException -> {
                ServerError(
                    statusCode = exception.response.status.value,
                    message =
                        when (exception.response.status.value) {
                            404 -> "Resource not found."
                            403 -> "Access denied."
                            401 -> "Authentication required."
                            in 500..599 -> "Server error. Please try again later."
                            else -> "Request failed."
                        },
                    debugInfo = exception.message,
                )
            }

            // Generic IO errors (KMP)
            is IOException -> {
                NetworkError(
                    message = "Network error. Check your connection.",
                    debugInfo = exception.message,
                )
            }

            // Kotlinx serialization (KMP)
            is SerializationException -> {
                DataError(
                    message = "Invalid data format.",
                    debugInfo = exception.message,
                )
            }

            // Fallback for unknown exceptions — use the exception's message verbatim so
            // callers surface the same text they would have seen pre-migration.
            else -> {
                UnknownError(
                    message = exception.message ?: "An unexpected error occurred",
                    debugInfo = exception.stackTraceToString(),
                )
            }
        }
}
