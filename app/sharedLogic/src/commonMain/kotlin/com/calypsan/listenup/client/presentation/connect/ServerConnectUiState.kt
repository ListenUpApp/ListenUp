package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.api.error.ServerConnectError

/**
 * UI state for the server connection screen.
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * The URL text input is owned by the Compose layer, not this state, so
 * the variants carry only what is specific to each phase.
 */
sealed interface ServerConnectUiState {
    /** Ready for user input. */
    data object Idle : ServerConnectUiState

    /** Server verification request in flight. */
    data object Verifying : ServerConnectUiState

    /**
     * Verification completed successfully.
     * The screen observes this and triggers navigation.
     */
    data object Verified : ServerConnectUiState

    /** Verification or validation failed. */
    data class Error(
        val error: ServerConnectError,
    ) : ServerConnectUiState
}
