package com.calypsan.listenup.client.domain.model

/**
 * Domain model for invite details.
 *
 * Contains information about a pending invite for display
 * on the registration screen.
 */
data class InviteDetails(
    /** The pre-filled display name for the user */
    val name: String,
    /** The pre-filled email address for the user */
    val email: String,
    /** The name of the server/library being joined */
    val serverName: String,
    /** Display name of the person who sent the invite */
    val invitedBy: String,
    /** Whether the invite is still valid (not claimed, not expired) */
    val valid: Boolean,
)
