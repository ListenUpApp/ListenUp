package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.data.remote.RpcFailureClassifier
import com.calypsan.listenup.client.data.remote.ServerUrlNotConfiguredException
import com.calypsan.listenup.client.data.remote.model.EnvelopeMismatchException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSocketException
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

            // TLS/SSL handshake or certificate failure: the socket connected but the secure channel
            // couldn't be established — typically an https/wss URL pointed at a plaintext server, or
            // a self-signed cert. Typed so verification can branch on it (retry the alternate scheme)
            // WITHOUT substring-matching a message. Placed above the IOException arm because platform
            // SSL exceptions (SSLException/SSLHandshakeException) are IOExceptions. See [isTlsFailure]
            // for why WebSocketException is explicitly excluded.
            isTlsFailure(exception) -> {
                ServerConnectError.TlsFailure(debugInfo = exception.message)
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

            // A dead/cancelled RpcClient ("RpcClient was cancelled") or a generic non-401 WebSocket
            // failure is a PRE-delivery transport fault: by the time it reaches here, RpcProxyCache has
            // already split out the post-delivery "outcome unknown" case (RpcOutcomeUnknownException),
            // so the frame never landed. Surface it as a retryable NetworkUnavailable — honest and
            // never-stranded — instead of a scary InternalError. Ordered AFTER the isWsHandshake401 arm
            // above so a 401 handshake still maps to SessionExpired.
            RpcFailureClassifier.isDeadRpcClient(exception) ||
                exception is WebSocketException -> {
                TransportError.NetworkUnavailable(debugInfo = exception.message)
            }

            // ONLY a dedicated client-validation exception earns the user-facing ValidationError.
            // A bare IllegalArgumentException (a library `require`, a mapper bug — message
            // "Failed requirement.") is an internal fault and must NOT be shown to the user as their
            // input problem; it falls through to the sanitized InternalError below.
            exception is ClientValidationException -> {
                ValidationError(
                    message = exception.userMessage,
                    field = exception.field,
                    debugInfo = exception.message,
                )
            }

            else -> {
                InternalError(debugInfo = "${exception::class.simpleName}: ${exception.message}")
            }
        }

    /**
     * True when [exception] (or a cause) is a TLS/SSL handshake or certificate failure.
     *
     * Detected by the raw platform exception's CLASS NAME (SSLException / SSLHandshakeException /
     * SSLPeerUnverifiedException on OkHttp, the Darwin secure-transport equivalents) rather than its
     * message. Matching a third-party exception TYPE is legitimate — the "never substring-match on
     * message" rule governs `AppError` bodies, not platform exceptions (see [RpcFailureClassifier]).
     *
     * A [WebSocketException] is explicitly excluded: it means TLS SUCCEEDED but the HTTP upgrade
     * returned a non-101 status (a proxy 500, etc.). Its message contains the word "Handshake", which
     * an earlier message-substring heuristic misread as an SSL error — the exact bug this replaces.
     */
    private fun isTlsFailure(exception: Throwable): Boolean {
        if (exception is WebSocketException) return false
        return generateSequence(exception) { it.cause }
            .mapNotNull { it::class.simpleName }
            .any { name -> TLS_CLASS_MARKERS.any { marker -> name.contains(marker, ignoreCase = true) } }
    }

    private val TLS_CLASS_MARKERS = listOf("SSL", "TLS", "Certificate")
}
