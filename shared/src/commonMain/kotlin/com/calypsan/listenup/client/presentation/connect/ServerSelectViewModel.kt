package com.calypsan.listenup.client.presentation.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.DiscoveredServer
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the server selection screen.
 *
 * Responsibilities:
 * - Start/stop mDNS discovery (after platform permission is confirmed)
 * - Observe discovered and persisted servers
 * - Handle server selection and activation
 *
 * State is derived reactively via `combine(...).stateIn(WhileSubscribed)`:
 * the server list comes from [ServerRepository.observeServers], overlaid
 * with transient UI concerns (selection, connection, error) tracked in a
 * private [Overlay] StateFlow.
 *
 * Discovery does **not** start automatically on construction. The screen
 * requests [android.permission.ACCESS_LOCAL_NETWORK] on first composition
 * and fires [ServerSelectUiEvent.LocalNetworkPermissionGranted] or
 * [ServerSelectUiEvent.LocalNetworkPermissionDenied] to the VM. On denial
 * the VM emits [ServerConnectError.LocalNetworkPermissionDenied] to the
 * global error bus and navigates to manual entry (Never Stranded).
 */
class ServerSelectViewModel(
    private val serverRepository: ServerRepository,
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val isDiscovering = MutableStateFlow(true)
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private var discoveryJob: Job? = null

    private sealed interface Overlay {
        data object None : Overlay

        /** Connection attempt to the server identified by [serverId] is in flight. */
        data class Connecting(
            val serverId: String,
        ) : Overlay

        /** Last connection attempt to [serverId] failed; [message] surfaces in the UI until dismissed. */
        data class Failed(
            val serverId: String,
            val message: String,
        ) : Overlay
    }

    val state: StateFlow<ServerSelectUiState> =
        combine(
            serverRepository.observeServers(),
            overlay,
            isDiscovering,
        ) { servers, current, discovering ->
            when (current) {
                is Overlay.Connecting -> {
                    ServerSelectUiState.Connecting(servers, current.serverId)
                }

                is Overlay.Failed -> {
                    ServerSelectUiState.Error(servers, current.serverId, current.message)
                }

                Overlay.None -> {
                    if (discovering) {
                        ServerSelectUiState.Discovering(servers)
                    } else {
                        ServerSelectUiState.Ready(servers)
                    }
                }
            }
        }.distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = ServerSelectUiState.Discovering(emptyList()),
            )

    private val _navigationEvents = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<NavigationEvent> = _navigationEvents.receiveAsFlow()

    /** Navigation events for the UI to handle. */
    sealed interface NavigationEvent {
        data object GoToManualEntry : NavigationEvent

        data object ServerActivated : NavigationEvent
    }

    /**
     * Start mDNS scanning and flip [isDiscovering] to false on the first
     * emission from the repository. Cancels any in-flight discovery watcher
     * so a rapid refresh cannot leave two coroutines racing.
     */
    private fun beginDiscovery() {
        logger.info { "Starting server discovery" }
        isDiscovering.value = true
        serverRepository.startDiscovery()
        discoveryJob?.cancel()
        discoveryJob =
            viewModelScope.launch {
                serverRepository.observeServers().take(1).collect {
                    isDiscovering.value = false
                }
            }
    }

    fun onEvent(event: ServerSelectUiEvent) {
        when (event) {
            is ServerSelectUiEvent.ServerSelected -> {
                handleServerSelected(event.server)
            }

            is ServerSelectUiEvent.DiscoveredServerSelected -> {
                handleDiscoveredServerSelected(event.server)
            }

            ServerSelectUiEvent.ManualEntryClicked -> {
                _navigationEvents.trySend(NavigationEvent.GoToManualEntry)
            }

            ServerSelectUiEvent.RefreshClicked -> {
                handleRefreshClicked()
            }

            ServerSelectUiEvent.ErrorDismissed -> {
                overlay.update { if (it is Overlay.Failed) Overlay.None else it }
            }

            ServerSelectUiEvent.LocalNetworkPermissionGranted -> {
                beginDiscovery()
            }

            ServerSelectUiEvent.LocalNetworkPermissionDenied -> {
                handleLocalNetworkPermissionDenied()
            }
        }
    }

    private fun handleLocalNetworkPermissionDenied() {
        logger.warn { "ACCESS_LOCAL_NETWORK permission denied — navigating to manual entry" }
        errorBus.emit(ServerConnectError.LocalNetworkPermissionDenied())
        isDiscovering.value = false
        _navigationEvents.trySend(NavigationEvent.GoToManualEntry)
    }

    private fun handleServerSelected(serverWithStatus: ServerWithStatus) {
        val server = serverWithStatus.server
        logger.info { "Server selected: ${server.name} (${server.id})" }

        val serverUrl = server.getBestUrl()
        if (serverUrl == null) {
            logger.error { "Server has no URL configured" }
            overlay.value = Overlay.Failed(server.id, "Server has no URL configured")
            return
        }

        overlay.value = Overlay.Connecting(server.id)

        viewModelScope.launch {
            try {
                serverRepository.setActiveServer(server.id)
                serverConfig.setServerUrl(ServerUrl(serverUrl))
                logger.info { "Server activated: ${server.id} at $serverUrl" }
                overlay.value = Overlay.None
                _navigationEvents.trySend(NavigationEvent.ServerActivated)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to activate server" }
                overlay.value = Overlay.Failed(server.id, "Failed to connect: ${e.message}")
            }
        }
    }

    private fun handleDiscoveredServerSelected(discovered: DiscoveredServer) {
        logger.info { "Discovered server selected: ${discovered.name} (${discovered.id})" }

        overlay.value = Overlay.Connecting(discovered.id)

        viewModelScope.launch {
            try {
                val urlsToTry =
                    buildList {
                        add(discovered.localUrl)
                        discovered.remoteUrl?.let { add(it) }
                    }

                val reachableUrl = instanceRepository.findReachableUrl(urlsToTry)

                if (reachableUrl != null) {
                    serverRepository.setActiveServer(discovered)
                    serverConfig.setServerUrl(ServerUrl(reachableUrl))
                    logger.info { "Server activated: ${discovered.id} at $reachableUrl" }
                    overlay.value = Overlay.None
                    _navigationEvents.trySend(NavigationEvent.ServerActivated)
                } else {
                    logger.warn { "Server discovered but not reachable at any URL: $urlsToTry" }
                    overlay.value =
                        Overlay.Failed(
                            discovered.id,
                            "Server found on network but not reachable. " +
                                "Try adding it manually with the server's IP address.",
                        )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to activate discovered server" }
                overlay.value = Overlay.Failed(discovered.id, "Failed to connect: ${e.message}")
            }
        }
    }

    private fun handleRefreshClicked() {
        logger.info { "Refresh discovery requested" }
        serverRepository.stopDiscovery()
        beginDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        logger.info { "Stopping server discovery" }
        serverRepository.stopDiscovery()
    }
}
