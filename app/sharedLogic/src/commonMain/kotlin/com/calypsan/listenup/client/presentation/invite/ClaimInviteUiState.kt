package com.calypsan.listenup.client.presentation.invite

import com.calypsan.listenup.api.dto.invite.InvitePreview

/**
 * UI state for the public invite claim flow (lookup → preview → claim).
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * Shown on the public landing page for an invite code, before the user
 * has any session.
 */
sealed interface ClaimInviteUiState {
    /** Initial state — awaiting an invite code. */
    data object Idle : ClaimInviteUiState

    /**
     * A deep link named a server this device is not already pointed at, and the user must agree
     * before it is persisted. [host] is the host part of the address, shown to the user — the
     * whole point of the step is that the address is visible before the client moves to it.
     * [signedInElsewhere] is true when a session for a DIFFERENT server is currently active, in
     * which case confirming signs that session out.
     */
    data class ConfirmServer(
        val host: String,
        val signedInElsewhere: Boolean,
    ) : ClaimInviteUiState

    /** Code lookup request in progress. */
    data object LookingUp : ClaimInviteUiState

    /** Lookup succeeded — show the invite [preview] and the claim form. */
    data class Preview(
        val preview: InvitePreview,
    ) : ClaimInviteUiState

    /** Claim request in progress. */
    data object Submitting : ClaimInviteUiState

    /**
     * Claim succeeded — the user is now logged in.
     * Navigation happens automatically via AuthState change.
     */
    data object Claimed : ClaimInviteUiState

    /** Lookup or claim failed; [message] is the user-facing error text. */
    data class Error(
        val message: String,
    ) : ClaimInviteUiState
}
