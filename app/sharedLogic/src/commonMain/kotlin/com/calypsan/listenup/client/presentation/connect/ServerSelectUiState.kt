package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.client.domain.model.ServerWithStatus

/**
 * UI state for the server selection screen.
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * The list of servers is part of every state because it remains visible
 * throughout: while scanning, while connecting, and when an error surfaces.
 */
sealed interface ServerSelectUiState {
    /** The servers currently known to the screen. Always present. */
    val servers: List<ServerWithStatus>

    /** Discovery scan is in progress and no server list has been emitted yet. */
    data class Discovering(
        override val servers: List<ServerWithStatus>,
    ) : ServerSelectUiState

    /** Server list has been emitted. Idle, ready for interaction. */
    data class Ready(
        override val servers: List<ServerWithStatus>,
    ) : ServerSelectUiState

    /** Activating the selected server. */
    data class Connecting(
        override val servers: List<ServerWithStatus>,
        val selectedServerId: String,
    ) : ServerSelectUiState

    /** Activation failed. */
    data class Error(
        override val servers: List<ServerWithStatus>,
        val selectedServerId: String,
        val message: String,
    ) : ServerSelectUiState
}

/**
 * User actions on the server selection screen.
 */
sealed interface ServerSelectUiEvent {
    /** User selected a server from the discovered list. */
    data class ServerSelected(
        val server: ServerWithStatus,
    ) : ServerSelectUiEvent

    /** User wants to enter a server URL manually. */
    data object ManualEntryClicked : ServerSelectUiEvent

    /** User wants to refresh discovery. */
    data object RefreshClicked : ServerSelectUiEvent

    /** Error was dismissed. */
    data object ErrorDismissed : ServerSelectUiEvent

    /**
     * The platform granted [android.permission.ACCESS_LOCAL_NETWORK] (or the
     * platform doesn't require it). Discovery can now start.
     */
    data object LocalNetworkPermissionGranted : ServerSelectUiEvent

    /**
     * The user denied [android.permission.ACCESS_LOCAL_NETWORK].
     * The VM will emit [ServerConnectError.LocalNetworkPermissionDenied] and
     * navigate to manual entry.
     */
    data object LocalNetworkPermissionDenied : ServerSelectUiEvent
}
