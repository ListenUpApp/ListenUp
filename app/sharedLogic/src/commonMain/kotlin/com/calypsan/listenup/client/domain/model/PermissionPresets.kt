package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.Permission

/**
 * A named starting point for a member's flags. Presets live only on the client and are never stored:
 * picking one sets the toggles, and the preset a user "has" is simply the one their flags match.
 *
 * Listener grants nothing; Contributor is exactly each permission's `defaultGranted` (so a new permission
 * lands in it by declaring its default); Librarian grants everything. [CUSTOM] is never picked — it is
 * what a flag set that matches no preset is called.
 */
enum class PermissionPreset {
    /** Listens and browses. Changes nothing. */
    LISTENER,

    /** The defaults: additive, undoable work on; merging and deleting off. */
    CONTRIBUTOR,

    /** Everything a member can do, including merging and deleting. */
    LIBRARIAN,

    /** Flags that match no preset. */
    CUSTOM,
    ;

    /** The presets an admin can pick, in screen order. */
    companion object {
        /** [LISTENER], [CONTRIBUTOR] and [LIBRARIAN] — never [CUSTOM]. */
        val pickable: List<PermissionPreset> = listOf(LISTENER, CONTRIBUTOR, LIBRARIAN)
    }
}

/** Whether [preset] grants [permission]. */
private fun PermissionPreset.grants(permission: Permission): Boolean =
    when (this) {
        PermissionPreset.LISTENER -> false
        PermissionPreset.CONTRIBUTOR -> permission.defaultGranted
        PermissionPreset.LIBRARIAN -> true
        PermissionPreset.CUSTOM -> error("CUSTOM is a reading, not a set of flags")
    }

/** [current] with every [advertised] permission set as this preset sets it; unadvertised flags keep their value. */
fun PermissionPreset.applyTo(
    current: UserPermissions,
    advertised: Set<Permission>,
): UserPermissions =
    if (this == PermissionPreset.CUSTOM) {
        current
    } else {
        Permission.known
            .filter { it in advertised }
            .fold(current) { flags, permission -> flags.granting(permission, grants(permission)) }
    }

/** The preset whose advertised flags [flags] match exactly, or [PermissionPreset.CUSTOM]. */
fun presetFor(
    flags: UserPermissions,
    advertised: Set<Permission>,
): PermissionPreset {
    val counted = Permission.known.filter { it in advertised }
    return PermissionPreset.pickable.firstOrNull { preset ->
        counted.all { flags.allows(it) == preset.grants(it) }
    } ?: PermissionPreset.CUSTOM
}

/** Presets only mean something when the server enforces more than one flag (spec §7.3). */
fun presetsApply(advertised: Set<Permission>): Boolean = Permission.known.count { it in advertised } > 1

/** How the admin user lists name someone: their role, or — for a member — their preset. */
enum class AccessLabel {
    /** The server's ROOT user. */
    OWNER,

    /** An ADMIN. */
    ADMIN,

    /** A MEMBER against a server where presets do not apply. */
    MEMBER,

    /** A MEMBER whose flags match [PermissionPreset.LISTENER]. */
    LISTENER,

    /** A MEMBER whose flags match [PermissionPreset.CONTRIBUTOR]. */
    CONTRIBUTOR,

    /** A MEMBER whose flags match [PermissionPreset.LIBRARIAN]. */
    LIBRARIAN,

    /** A MEMBER whose flags match no preset. */
    CUSTOM,
}

/** The label the user lists show for [user], given what the server [advertised]. */
fun accessLabelFor(
    user: AdminUserInfo,
    advertised: Set<Permission>,
): AccessLabel =
    when {
        user.isRoot -> {
            AccessLabel.OWNER
        }

        user.role.equals("ADMIN", ignoreCase = true) -> {
            AccessLabel.ADMIN
        }

        !presetsApply(advertised) -> {
            AccessLabel.MEMBER
        }

        else -> {
            when (presetFor(user.permissions, advertised)) {
                PermissionPreset.LISTENER -> AccessLabel.LISTENER
                PermissionPreset.CONTRIBUTOR -> AccessLabel.CONTRIBUTOR
                PermissionPreset.LIBRARIAN -> AccessLabel.LIBRARIAN
                PermissionPreset.CUSTOM -> AccessLabel.CUSTOM
            }
        }
    }
