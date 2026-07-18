package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.PlatformUtils
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.URLParserException
import io.ktor.http.Url
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
 *
 * The URL text input is owned by the screen (Compose `rememberSaveable`),
 * not this ViewModel. Callers pass the current URL into [submitUrl].
 */
class ServerConnectViewModel(
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {
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

        // Runs on [appScope], NOT viewModelScope: a successful verify calls setServerUrl, which flips the
        // global auth state (→ CheckingServer → NeedsLogin). That swap tears this screen — and its
        // viewModelScope — down mid-flight, so on viewModelScope the activation would cancel itself
        // before completing, stranding the app on the "Checking server" spinner.
        appScope.launch {
            state.value = ServerConnectUiState.Verifying

            state.value =
                when (val result = instanceRepository.verifyServer(url)) {
                    is AppResult.Success -> {
                        serverConfig.setServerUrl(ServerUrl(result.data.verifiedUrl))
                        // Persist the server's stable instance id so ConnectionCoordinator can
                        // IP-follow this manually-entered server when its LAN address changes.
                        // Relocation matches the mDNS-advertised id, which is the SAME
                        // InstanceIdentity as ServerInfo.instanceId — so without this, a manually
                        // connected server has a null connectedServerId and never relocates.
                        serverConfig.setConnectedServerId(result.data.serverInfo.instanceId)
                        ServerConnectUiState.Verified
                    }

                    is AppResult.Failure -> {
                        ServerConnectUiState.Error(mapFailure(result, url))
                    }
                }
        }
    }

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

    private fun mapFailure(
        result: AppResult.Failure,
        url: String,
    ): ServerConnectError =
        when (val error = result.error) {
            is TransportError.NetworkUnavailable, is TransportError.Timeout -> {
                ServerConnectError.ServerNotReachable(debugInfo = "Server not reachable at $url")
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

    companion object {
        private const val HTTP_NOT_FOUND = 404
    }
}
