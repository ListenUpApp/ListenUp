package com.calypsan.listenup.client.presentation.auth

/**
 * UI state for the registration screen.
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * Only shown when open registration is enabled on the server.
 */
sealed interface RegisterUiState {
    /** Initial state — ready for user input. */
    data object Idle : RegisterUiState

    /** Registration request in progress. */
    data object Loading : RegisterUiState

    /**
     * Registration submitted successfully.
     * AuthState has been updated to PendingApproval.
     * Navigation will automatically show PendingApprovalScreen.
     */
    data object Success : RegisterUiState

    /** Registration failed. */
    data class Error(
        val message: String,
    ) : RegisterUiState
}
