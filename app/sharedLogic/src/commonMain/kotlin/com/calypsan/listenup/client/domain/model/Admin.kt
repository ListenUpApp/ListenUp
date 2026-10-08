package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.Permission

/**
 * The signed-in user's, or an administered user's, permission flags — the domain twin of the contract
 * `UserPermissions`, one field per known `Permission`. ROOT and ADMIN hold every permission whatever
 * these say; [com.calypsan.listenup.client.domain.repository.PermissionsRepository] is the one place a
 * ViewModel asks "may I?".
 *
 * @property canEditMetadata Edit metadata: book fields, covers, chapters, matching, and editing (not
 *   merging or deleting) catalogue entries.
 * @property canCurateLibrary Curate library: merge, unmerge and delete contributors, series, genres,
 *   tags and moods, and undo those merges.
 * @property canContributeStoryWorld Contribute to Story World: create, edit and delete entries, and revert
 *   any change but a merge.
 * @property canCurateStoryWorld Curate Story World: merge entries, and revert a merge.
 * @property canMakeReadingOrders Make reading orders, and edit the ones you made (#962). On by default.
 */
data class UserPermissions(
    val canEditMetadata: Boolean = true,
    val canCurateLibrary: Boolean = false,
    val canContributeStoryWorld: Boolean = true,
    val canCurateStoryWorld: Boolean = false,
    val canMakeReadingOrders: Boolean = true,
) {
    /** Whether these flags grant [permission]. [Permission.UNKNOWN] is never granted. */
    fun allows(permission: Permission): Boolean =
        when (permission) {
            Permission.EDIT_METADATA -> canEditMetadata
            Permission.CURATE_LIBRARY -> canCurateLibrary
            Permission.CONTRIBUTE_STORY_WORLD -> canContributeStoryWorld
            Permission.CURATE_STORY_WORLD -> canCurateStoryWorld
            Permission.MAKE_READING_ORDERS -> canMakeReadingOrders
            Permission.UNKNOWN -> false
        }

    /** These flags with [permission] set to [granted]. [Permission.UNKNOWN] changes nothing. */
    fun granting(
        permission: Permission,
        granted: Boolean,
    ): UserPermissions =
        when (permission) {
            Permission.EDIT_METADATA -> copy(canEditMetadata = granted)
            Permission.CURATE_LIBRARY -> copy(canCurateLibrary = granted)
            Permission.CONTRIBUTE_STORY_WORLD -> copy(canContributeStoryWorld = granted)
            Permission.CURATE_STORY_WORLD -> copy(canCurateStoryWorld = granted)
            Permission.MAKE_READING_ORDERS -> copy(canMakeReadingOrders = granted)
            Permission.UNKNOWN -> this
        }
}

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
 * @property access How the admin lists name this user — their role, or a member's preset ([accessLabelFor]).
 *   Filled in by the ViewModels that know what the server advertises.
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
    val access: AccessLabel = AccessLabel.MEMBER,
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
