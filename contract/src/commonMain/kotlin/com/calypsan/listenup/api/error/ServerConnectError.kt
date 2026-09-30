package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for the initial server-connect flow (URL entry -> verify -> save).
 *
 * These errors guide users through server setup, distinguishing user-fixable
 * input problems (bad URL) from environmental ones (server down, wrong host).
 * They are inherently client-local — the wire never carries a
 * [ServerConnectError] — so [correlationId] is null by default.
 */
@Serializable
sealed interface ServerConnectError : AppError {
    /**
     * The URL the user entered failed pre-flight validation.
     *
     * [reason] is a stable category embedded in [code] so analytics can
     * distinguish causes; known values include `"blank"`, `"localhost_physical"`,
     * and `"malformed"`.
     */
    @Serializable
    @SerialName("ServerConnectError.InvalidUrl")
    data class InvalidUrl(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val reason: String,
    ) : ServerConnectError {
        override val message: String =
            when (reason) {
                "blank" -> "Please enter a server URL."
                "localhost_physical" -> "Localhost only works on the emulator. Use your computer's IP address."
                "malformed" -> "That URL doesn't look right. Please check and try again."
                else -> "That URL isn't valid."
            }
        override val code: String = "SERVER_CONNECT_INVALID_URL_${reason.uppercase()}"
        override val isRetryable: Boolean = false
    }

    /**
     * The server responded but doesn't look like a ListenUp server.
     *
     * Typical causes: 404 on `/api/v1/instance`, schema mismatch, or a
     * non-ListenUp service occupying the host.
     */
    @Serializable
    @SerialName("ServerConnectError.NotListenUpServer")
    data class NotListenUpServer(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message: String = "This doesn't appear to be a ListenUp server."
        override val code: String = "SERVER_CONNECT_NOT_LISTENUP_SERVER"
        override val isRetryable: Boolean = false
    }

    /**
     * The server is not reachable: connection refused, host not found, or
     * timeout. May be transient (server starting up, brief network glitch),
     * so an automatic retry can succeed.
     */
    @Serializable
    @SerialName("ServerConnectError.ServerNotReachable")
    data class ServerNotReachable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message: String = "Cannot reach server. Check that it's running and the URL is correct."
        override val code: String = "SERVER_CONNECT_NOT_REACHABLE"
        override val isRetryable: Boolean = true
    }

    /**
     * Verification failed for a generic reason not covered by the more
     * specific subtypes (e.g., transient network blip during the
     * handshake). Auto-retry can succeed.
     */
    @Serializable
    @SerialName("ServerConnectError.VerificationFailed")
    data class VerificationFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message: String = "Couldn't verify the server. Please try again."
        override val code: String = "SERVER_CONNECT_VERIFICATION_FAILED"
        override val isRetryable: Boolean = true
    }

    /**
     * The secure channel could not be established: the TLS/SSL handshake failed
     * or the certificate was rejected. Typically the user pointed an `https`/`wss`
     * URL at a plaintext server, or the server presents a self-signed certificate.
     *
     * Distinct from a non-101 WebSocket upgrade response (proxy 500, etc.): those
     * mean TLS *succeeded* but the HTTP upgrade was refused, and must NOT be treated
     * as a scheme mismatch. Verification uses this typed variant — not a message
     * substring — to decide whether to retry the alternate (http/ws) scheme.
     */
    @Serializable
    @SerialName("ServerConnectError.TlsFailure")
    data class TlsFailure(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message: String = "Couldn't establish a secure connection to the server."
        override val code: String = "SERVER_CONNECT_TLS_FAILURE"
        override val isRetryable: Boolean = false
    }

    /**
     * The operating system is blocking ListenUp from the user's local network because the user
     * has not allowed it — Android 17's `ACCESS_LOCAL_NETWORK` permission, or iOS's Local Network
     * privacy setting.
     *
     * The gate covers both halves of connecting: discovery (mDNS/Bonjour) and any connection to a
     * LAN address, including one the user typed by hand. Neither platform reports the block on a
     * connection — a denied connect just times out — so the connect screens raise this only
     * after a connect has failed and the platform confirms the gate was the cause; a server
     * reached over a VPN is never blamed on it.
     *
     * Not retryable by middleware: the user has to allow access (the permission dialog on
     * Android, Settings on iOS). The connect screens offer that action and re-run the attempt
     * when access comes back.
     */
    @Serializable
    @SerialName("ServerConnectError.LocalNetworkPermissionDenied")
    data class LocalNetworkPermissionDenied(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ServerConnectError {
        override val message: String =
            "ListenUp needs local network access to find and connect to servers on your network."
        override val code: String = "SERVER_CONNECT_LOCAL_NETWORK_PERMISSION_DENIED"
        override val isRetryable: Boolean = false
    }
}
