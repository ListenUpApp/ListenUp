package com.calypsan.listenup.client.domain.model

/**
 * User permission flags for action-level access control.
 *
 * @property canShare Whether user can share collections with other users
 */
data class UserPermissions(
    val canShare: Boolean = true,
)

/**
 * Domain model representing a user in the admin context.
 *
 * This is a simplified user representation for admin screens that manage
 * users, pending approvals, and user deletion. Contains only the fields
 * needed for admin operations.
 *
 * @property id Unique user identifier
 * @property email User's email address
 * @property displayName User's display name (optional)
 * @property firstName User's first name (optional)
 * @property lastName User's last name (optional)
 * @property isRoot Whether this is the root/super admin user
 * @property role User's role in the system
 * @property status User's current status (active, pending, etc.)
 * @property permissions User's permission flags
 * @property createdAt Creation timestamp as ISO string
 */
data class AdminUserInfo(
    val id: String,
    val email: String,
    val displayName: String?,
    val firstName: String?,
    val lastName: String?,
    val isRoot: Boolean,
    val role: String,
    val status: String,
    val permissions: UserPermissions = UserPermissions(),
    val createdAt: String,
) {
    /**
     * Returns a display-friendly name using the best available option:
     * displayName > "firstName lastName" > email
     */
    val displayableName: String
        get() =
            displayName?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(firstName, lastName)
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() }
                ?: email

    /**
     * Whether this user is protected from deletion/modification.
     * Root users cannot be modified except by themselves.
     */
    val isProtected: Boolean
        get() = isRoot
}

/**
 * Domain model representing an invite code for user registration.
 *
 * Invites allow admins to control who can register on a server.
 * They can be limited by use count and/or expiration date.
 *
 * @property id Unique invite identifier
 * @property code The invite code users enter to register
 * @property name Display name for the invite
 * @property email Email this invite is restricted to
 * @property role Role assigned to users who use this invite
 * @property expiresAt Expiration timestamp as ISO string
 * @property claimedAt When the invite was claimed (null if unclaimed)
 * @property url Full invite URL for sharing
 * @property createdAt Creation timestamp as ISO string
 */
data class InviteInfo(
    val id: String,
    val code: String,
    val name: String,
    val email: String,
    val role: String,
    val expiresAt: String,
    val claimedAt: String?,
    val url: String,
    val createdAt: String,
) {
    /**
     * Returns true if this invite has not been claimed yet.
     */
    val isPending: Boolean
        get() = claimedAt == null
}
