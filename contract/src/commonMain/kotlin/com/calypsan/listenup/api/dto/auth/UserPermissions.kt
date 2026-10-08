package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-user action permissions, independent of [UserRole]. ROOT and ADMIN implicitly hold every
 * permission; these flags grant capabilities to MEMBER users. One field per known [Permission], named
 * on the wire by its [Permission.wireKey].
 *
 * **The defaults rule** (see [Permission]): additive, undoable work defaults on; destructive or
 * library-wide work defaults off. A new flag's default follows it, so neither an older server's response
 * nor an older admin client can grant a destructive power by omission. Admin changes go through
 * [UserPermissionsPatch], whose null fields mean unchanged.
 *
 * @property canEditMetadata [Permission.EDIT_METADATA]. Serialized as `canEdit`, the name every older
 *   client and server already speaks.
 * @property canCurateLibrary [Permission.CURATE_LIBRARY]. Off by default.
 */
@Serializable
data class UserPermissions(
    @SerialName("canEdit") val canEditMetadata: Boolean = true,
    @SerialName("canCurateLibrary") val canCurateLibrary: Boolean = false,
)

/** Whether these flags grant [permission]. [Permission.UNKNOWN] is never granted. */
fun UserPermissions.allows(permission: Permission): Boolean =
    when (permission) {
        Permission.EDIT_METADATA -> canEditMetadata
        Permission.CURATE_LIBRARY -> canCurateLibrary
        Permission.UNKNOWN -> false
    }
