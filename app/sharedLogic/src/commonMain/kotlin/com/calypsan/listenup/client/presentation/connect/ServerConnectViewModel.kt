package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.client.domain.usecase.auth.AdoptServerUseCase
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.PlatformUtils
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.URLParserException
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the server connection screen.
 *
 * Thin coordinator that:
 * - Manages UI state as a sealed [ServerConnectUiState] hierarchy
 * - Validates URL format and accessibility
 * - Verifies the server is a ListenUp instance via [InstanceRepository]
 * - Saves the verified URL to [ServerConfig]
 * - Names a connect the OS blocked for want of local-network permission, via [LocalNetworkAccess]
 *
 * The URL text input is owned by the screen (Compose `rememberSaveable`),
 * not this ViewModel. Callers pass the current URL into [submitUrl].
 */
class ServerConnectViewModel(
    private val adoptServer: AdoptServerUseCase,
    private val instanceRepository: InstanceRepository,
    private val localNetworkAccess: LocalNetworkAccess,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private var closed = false

    /** The URL of the last attempt that reached the network, so a grant can re-run it. */
    private var lastAttemptedUrl: String? = null

    /**
     * Idempotent teardown hook the iOS wrapper calls from its `isolated deinit` (#1192). This VM runs
     * its verify/activate work on [appScope] by design — it must outlive the screen so activation is
     * not cancelled mid-flight — so there is no screen-scoped coroutine to cancel here.
     */
    fun close() {
        if (closed) return
        closed = true
    }

    val state: StateFlow<ServerConnectUiState>
        field = MutableStateFlow<ServerConnectUiState>(ServerConnectUiState.Idle)

    /**
     * Submit a URL for validation and server verification.
     *
     * Two-phase:
     * 1. Local validation (format, localhost on physical device)
     * 2. Network verification via [InstanceRepository.verifyServer]
     */
    fun submitUrl(rawUrl: String) {
        val url = rawUrl.trim()

        val validationError = validateUrl(url)
        if (validationError != null) {
            state.value = ServerConnectUiState.Error(validationError)
            return
        }

        lastAttemptedUrl = url
        // Set before the launch, not inside it: a second submit or retry arriving before this one
        // is dispatched must see an attempt in flight, or both would verify and adopt.
        state.value = ServerConnectUiState.Verifying

        // Runs on [appScope], NOT viewModelScope: a successful verify calls setServerUrl, which flips the
        // global auth state (→ CheckingServer → NeedsLogin). That swap tears this screen — and its
        // viewModelScope — down mid-flight, so on viewModelScope the activation would cancel itself
        // before completing, stranding the app on the "Checking server" spinner.
        appScope.launch {
            state.value =
                when (val result = instanceRepository.verifyServer(url)) {
                    is AppResult.Success -> {
                        // The instance id both arms IP-follow (ConnectionCoordinator relocates by the
                        // mDNS-advertised id, the same InstanceIdentity as ServerInfo.instanceId) and
                        // tells a different server from this one — which starts from a clean library.
                        adoptServer(url = result.data.verifiedUrl, instanceId = result.data.serverInfo.instanceId)
                        ServerConnectUiState.Verified
                    }

                    is AppResult.Failure -> {
                        ServerConnectUiState.Error(mapFailure(result, url))
                    }
                }
        }
    }

    /**
     * Re-run the last attempt if — and only if — it failed because local network access was
     * denied. The screens call this whenever access may have come back: on Android when the
     * permission reads granted after the dialog or Settings, on iOS each time the app returns to
     * the foreground (iOS has no way to read the permission, so a still-denied retry simply fails
     * the same way again).
     */
    fun retryAfterLocalNetworkGrant() {
        val url = lastAttemptedUrl ?: return
        val blocked = (state.value as? ServerConnectUiState.Error)?.error
        if (blocked is ServerConnectError.LocalNetworkPermissionDenied) submitUrl(url)
    }

    /**
     * Whether [rawUrl] is clearly a server off the local network — see
     * [isClearlyRemoteServerAddress]. The iOS manual-entry sheet uses it to set aside its Local
     * Network notice while a remote address is typed.
     */
    fun isClearlyRemoteAddress(rawUrl: String): Boolean = isClearlyRemoteServerAddress(rawUrl)

    /** Clear any error state so the user can retry. */
    fun clearError() {
        if (state.value is ServerConnectUiState.Error) {
            state.value = ServerConnectUiState.Idle
        }
    }

    /**
     * Validate URL format and accessibility.
     *
     * - Not blank
     * - Valid URL syntax (protocol added automatically if missing)
     * - Not localhost on a physical device
     */
    private fun validateUrl(url: String): ServerConnectError? {
        if (url.isBlank()) {
            return ServerConnectError.InvalidUrl(reason = "blank")
        }

        val urlWithProtocol =
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

        try {
            Url(urlWithProtocol)
        } catch (e: URLParserException) {
            logger.debug(e) { "URL validation failed for: $url" }
            return ServerConnectError.InvalidUrl(reason = "malformed")
        }

        val isLocalhost = url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0")
        if (isLocalhost && !PlatformUtils.isEmulator()) {
            return ServerConnectError.InvalidUrl(reason = "localhost_physical")
        }

        return null
    }

    private suspend fun mapFailure(
        result: AppResult.Failure,
        url: String,
    ): ServerConnectError =
        when (val error = result.error) {
            // A connect the OS's local-network gate blocks never errors — it times out, or the
            // socket reports no route — which reads exactly like a dead server. Only now, after
            // that failure, ask whether the gate explains it: asking up front would misjudge a
            // server reached over a VPN, whose interface is never gated.
            is TransportError.NetworkUnavailable, is TransportError.Timeout -> {
                if (isBlockedByLocalNetworkGate(url)) {
                    ServerConnectError.LocalNetworkPermissionDenied(
                        debugInfo = "Local network access denied; ${error.code} connecting to $url",
                    )
                } else {
                    ServerConnectError.ServerNotReachable(debugInfo = "Server not reachable at $url")
                }
            }

            is TransportError.DataMalformed -> {
                ServerConnectError.NotListenUpServer(debugInfo = "Failed to parse server response: ${error.detail}")
            }

            is TransportError.Server4xx -> {
                if (error.statusCode == HTTP_NOT_FOUND) {
                    ServerConnectError.NotListenUpServer(debugInfo = "Server returned 404 — endpoint absent")
                } else {
                    ServerConnectError.VerificationFailed(debugInfo = error.debugInfo ?: error.message)
                }
            }

            else -> {
                ServerConnectError.VerificationFailed(debugInfo = error.debugInfo ?: error.message)
            }
        }

    private suspend fun isBlockedByLocalNetworkGate(url: String): Boolean {
        val target = connectTarget(url) ?: return false
        // The gate only refines an error that already happened. If asking it fails, fall back to
        // the plain verdict rather than leaving the screen stuck on "Verifying".
        return try {
            localNetworkAccess.isDeniedFor(target.host, target.port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Local network gate could not answer for $url; reporting the server unreachable" }
            false
        }
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404
    }
}

/**
 * Where a typed URL connects: the bare [host] (no URL brackets, IPv6 zone decoded) and the [port].
 */
internal data class ConnectTarget(
    val host: String,
    val port: Int,
)

/**
 * The host and port a typed URL connects to, with the scheme defaulted the way
 * [InstanceRepository.verifyServer] tries it first: plain `http` for an IP address (a LAN box
 * rarely has a certificate), `https` for a name. Null when the URL cannot be parsed.
 */
internal fun connectTarget(rawUrl: String): ConnectTarget? {
    val withScheme =
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else if (rawUrl.substringBefore(':').all { it.isDigit() || it == '.' }) {
            "http://$rawUrl"
        } else {
            "https://$rawUrl"
        }
    val url =
        try {
            Url(withScheme)
        } catch (e: URLParserException) {
            logger.debug(e) { "Could not derive a connect target from $rawUrl" }
            return null
        }
    return ConnectTarget(bareHost(url.host), url.port)
}

/**
 * A URL host as the network APIs want it: Ktor keeps an IPv6 literal's brackets (`[fd00::5]`) and
 * its zone percent-encoded (`%25en0`), and `nw_endpoint_create_host` accepts neither.
 */
private fun bareHost(urlHost: String): String = urlHost.removePrefix("[").removeSuffix("]").replace("%25", "%")
