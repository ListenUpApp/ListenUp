package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier
import com.calypsan.listenup.client.data.remote.ServerUrlNotConfiguredException
import com.calypsan.listenup.client.data.remote.model.EnvelopeMismatchException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
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
internal object ErrorMapper {
    fun map(exception: Throwable): AppError =
        when {
            // A handshake-401 WebSocketException is a stale-session signal from the `/api/rpc/authed`
            // upgrade. After RpcProxyCache's one bounded token-refresh + retry, a SECOND 401 reaches
            // here — surface it typed as SessionExpired so the global auth observer drives to login,
            // instead of a generic InternalError. Checked first: it is a WebSocketException, which the
            // arms below would otherwise fold into the InternalError catch-all.
            RpcFailureClassifier.isWsHandshake401(exception) -> {
                AuthError.SessionExpired(debugInfo = exception.message)
            }

            exception is ConnectTimeoutException ||
                exception is SocketTimeoutException ||
                exception is HttpRequestTimeoutException -> {
                TransportError.Timeout(debugInfo = exception.message)
            }

            exception is ResponseException -> {
                val status = exception.response.status.value
                when {
                    // 401 means the session is stale/invalid — type it as an auth error at the
                    // boundary so the global auth-failure observer drives the app back to login
                    // instead of looping a generic "server error" snackbar. 403
                    // (authenticated-but-forbidden) stays a plain 4xx — it is not a session failure.
                    status == HttpStatusCode.Unauthorized.value -> {
                        AuthError.SessionExpired(debugInfo = exception.message)
                    }

                    status in 500..599 -> {
                        TransportError.Server5xx(statusCode = status, debugInfo = exception.message)
                    }

                    else -> {
                        TransportError.Server4xx(statusCode = status, debugInfo = exception.message)
                    }
                }
            }

            // A well-formed 2xx whose envelope this client can't parse (version/shape skew) is
            // evidence of a contract mismatch, not an internal fault — surfaced as the
            // non-blocking "Update available" hint. Checked before the SerializationException arm
            // below since it is the more specific case.
            exception is EnvelopeMismatchException -> {
                TransportError.ContractMismatch(
                    detail = exception.message ?: "envelope mismatch",
                    debugInfo = exception.message,
                )
            }

            exception is SerializationException -> {
                TransportError.DataMalformed(
                    detail = exception.message ?: "deserialization failed",
                    debugInfo = exception.message,
                )
            }

            exception is IOException -> {
                TransportError.NetworkUnavailable(debugInfo = exception.message)
            }

            // "No server configured yet" is an expected pre-connection state (fresh / signed-out
            // install), not a fault — type it as a transport-unavailable so the boundary folds it
            // quietly instead of surfacing a generic InternalError "server error".
            exception is ServerUrlNotConfiguredException -> {
                TransportError.NetworkUnavailable(debugInfo = exception.message)
            }

            exception is IllegalArgumentException -> {
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
