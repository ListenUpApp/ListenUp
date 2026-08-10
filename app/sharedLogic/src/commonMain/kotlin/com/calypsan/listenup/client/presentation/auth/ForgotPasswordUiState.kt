package com.calypsan.listenup.client.presentation.auth

/**
 * Screen state for the forgot-password flow: request, wait for an admin, then complete with the
 * out-of-band code the admin conveys.
 */
sealed interface ForgotPasswordUiState {
    /** Initial: asking for the account's email address. */
    data object EnterEmail : ForgotPasswordUiState

    /** The request is in flight. */
    data object Submitting : ForgotPasswordUiState

    /**
     * Waiting for an admin. The user may close the app here and come back — the device claim is
     * persisted, so [ticketId] plus the code is enough to finish later.
     */
    data class AwaitingApproval(
        val ticketId: String,
    ) : ForgotPasswordUiState

    /** Approved. Collecting the code the admin conveyed, plus the new password. */
    data class EnterCode(
        val ticketId: String,
        val attemptsRemaining: Int? = null,
        val error: String? = null,
    ) : ForgotPasswordUiState

    /** The admin declined. The request is dead; the requester must start over. */
    data object Denied : ForgotPasswordUiState

    /** Done — the user signs in with the new password. */
    data object Complete : ForgotPasswordUiState

    /** Terminal failure with a user-facing message. */
    data class Error(
        val message: String,
    ) : ForgotPasswordUiState
}
