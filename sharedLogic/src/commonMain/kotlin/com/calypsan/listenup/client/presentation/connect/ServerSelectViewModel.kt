package com.calypsan.listenup.client.presentation.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    private val appScope: CoroutineScope,
) : ViewModel() {
    // Starts true even though discovery hasn't begun yet. The permission dialog
    // is modal and covers this pre-discovery window; flipping to false here
    // would falsely report Ready before any servers are known. The denial
    // handler resets it to false explicitly.
    private val isDiscovering = MutableStateFlow(true)
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private var discoveryJob: Job? = null
    private var closed = false

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
        overlay.value = Overlay.None
        _navigationEvents.trySend(NavigationEvent.GoToManualEntry)
    }

    private fun handleServerSelected(serverWithStatus: ServerWithStatus) {
        val server = serverWithStatus.server
        logger.info { "Server selected: ${server.name} (${server.id})" }

        val urlsToTry =
            buildList {
                // Every resolved LAN address (best-first), so an unreachable primary — an
                // advertised-but-unroutable address, or a server that has moved — falls back to a
                // reachable one instead of stranding the user on the spinner.
                addAll(server.localUrls.ifEmpty { listOfNotNull(server.localUrl) })
                server.remoteUrl?.let { add(it) }
            }
        if (urlsToTry.isEmpty()) {
            logger.error { "Server has no URL configured" }
            overlay.value = Overlay.Failed(server.id, "Server has no URL configured")
            return
        }

        overlay.value = Overlay.Connecting(server.id)

        // Runs on [appScope], NOT viewModelScope: activating the server flips the global auth state
        // (NeedsServerUrl → CheckingServer → NeedsLogin), and the CheckingServer swap tears this screen
        // — and its viewModelScope — down mid-flight. On viewModelScope the activation would cancel
        // itself before checkServerStatus finished, stranding the app on the "Checking server" spinner.
        appScope.launch {
            try {
                val reachableUrl = instanceRepository.findReachableUrl(urlsToTry)

                if (reachableUrl != null) {
                    serverConfig.setServerUrl(ServerUrl(reachableUrl))
                    serverConfig.setConnectedServerId(server.id)
                    logger.info { "Server activated: ${server.id} at $reachableUrl" }
                    overlay.value = Overlay.None
                    _navigationEvents.trySend(NavigationEvent.ServerActivated)
                } else {
                    logger.warn { "Server discovered but not reachable at any URL: $urlsToTry" }
                    overlay.value =
                        Overlay.Failed(
                            server.id,
                            "Server found on network but not reachable. " +
                                "Try adding it manually with the server's IP address.",
                        )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to activate server" }
                overlay.value = Overlay.Failed(server.id, "Failed to connect: ${e.message}")
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
        close()
    }

    /**
     * Stops mDNS discovery and cancels the discovery job. Idempotent — safe to call more than once.
     *
     * Android/Desktop reach this via [onCleared] when the `ViewModelStore` clears the entry. iOS has
     * no `ViewModelStore`, so [ServerSelectViewModelWrapper] calls this from its `isolated deinit`
     * (#1192); without it the mDNS discovery keeps announcing/scanning forever after the screen goes.
     */
    fun close() {
        if (closed) return
        closed = true
        logger.info { "Stopping server discovery" }
        serverRepository.stopDiscovery()
        viewModelScope.cancel()
    }
}
