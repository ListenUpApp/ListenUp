package com.calypsan.listenup.core.error

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Map exceptions to unified [AppError] subtypes.
 *
 * Pure function over the exception hierarchy; produces wire-shaped errors
 * with `correlationId = null` (these are client-local mappings — server-issued
 * correlation ids arrive in deserialized [AppError] payloads from the RPC
 * exception interceptor, not via this mapper).
 */
object ErrorMapper {
    fun map(exception: Throwable): AppError =
        when (exception) {
            is ConnectTimeoutException,
            is SocketTimeoutException,
            is HttpRequestTimeoutException,
            -> {
                TransportError.Timeout(debugInfo = exception.message)
            }

            is ResponseException -> {
                val status = exception.response.status.value
                if (status in 500..599) {
                    TransportError.Server5xx(statusCode = status, debugInfo = exception.message)
                } else {
                    TransportError.Server4xx(statusCode = status, debugInfo = exception.message)
                }
            }

            is SerializationException -> {
                TransportError.DataMalformed(
                    detail = exception.message ?: "deserialization failed",
                    debugInfo = exception.message,
                )
            }

            is IOException -> {
                TransportError.NetworkUnavailable(debugInfo = exception.message)
            }

            is IllegalArgumentException -> {
                ValidationError(
                    message = exception.message ?: "Invalid input.",
                    debugInfo = exception.message,
                )
            }

            else -> {
                InternalError(debugInfo = "${exception::class.simpleName}: ${exception.message}")
            }
        }
}
