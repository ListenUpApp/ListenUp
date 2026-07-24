package com.calypsan.listenup.client.presentation.auth

/**
 * UI state for the pending approval screen.
 *
 * The screen waits for the server-side approval-status watch (RPC stream)
 * to flip the registration to approved or denied. There is no client-side
 * auto-login; once approved, the user logs in normally from the login screen.
 */
sealed interface PendingApprovalUiState {
    /** Awaiting admin approval. */
    data object Waiting : PendingApprovalUiState

    /** Registration was approved. The screen prompts the user to log in. */
    data object Approved : PendingApprovalUiState

    /** Registration was denied. */
    data class Denied(
        val message: String,
    ) : PendingApprovalUiState
}
