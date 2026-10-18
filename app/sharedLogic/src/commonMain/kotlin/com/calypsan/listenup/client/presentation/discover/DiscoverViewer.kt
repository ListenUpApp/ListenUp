package com.calypsan.listenup.client.presentation.discover

/**
 * The reader looking at Discover, as its social sections need them: whose avatar sits in the only-you
 * invitation, and whether "Invite someone" is theirs to offer (only admins invite).
 *
 * @property userId The signed-in reader.
 * @property displayName Their display name, the avatar's fallback initials.
 * @property canInvite True for an admin (or root): the invitation offers "Invite someone".
 */
data class DiscoverViewer(
    val userId: String,
    val displayName: String,
    val canInvite: Boolean,
)
