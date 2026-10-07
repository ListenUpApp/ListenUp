package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An admin's change to one user's [UserPermissions]: every field is nullable, and **null means
 * unchanged**. The server merges each field independently ([patchedBy]).
 *
 * Why not a whole [UserPermissions]: its fields default, so a body naming one flag would decode the
 * rest as their defaults and silently grant or revoke them — exactly what an older admin client sending
 * `{"canEdit": false}` would do to a flag added after it shipped. Here an absent key is `null`, and
 * `null` leaves the stored value alone.
 *
 * Clients send only the flags that changed, and render a toggle only for a flag the server advertises
 * ([com.calypsan.listenup.api.dto.ServerInfo.permissionFlags]). An older server, which decodes this
 * body as a whole [UserPermissions], therefore only ever receives `canEdit`.
 *
 * **Changing [canEditMetadata] names [canCurateLibrary] too**, whenever the server advertises it. The
 * server reads `canEditMetadata = false` with curate unnamed as an older admin app's "Can edit" off,
 * which revoked merge and delete as well, so it turns curate off too.
 *
 * **Never send an empty patch.** When no flag changed, send `permissions = null` on
 * [AdminUserPatch]. A present-but-empty object is what an older admin app puts on the wire for
 * `UserPermissions(canEdit = true)` (defaults are not encoded), so the server reads `{}` as "grant
 * Edit metadata", not as "change nothing".
 *
 * @property canEditMetadata see [UserPermissions.canEditMetadata]; null leaves it unchanged.
 * @property canCurateLibrary see [UserPermissions.canCurateLibrary]; null leaves it unchanged.
 * @property canContributeStoryWorld see [UserPermissions.canContributeStoryWorld]; null leaves it unchanged.
 * @property canCurateStoryWorld see [UserPermissions.canCurateStoryWorld]; null leaves it unchanged.
 * @property canMakeReadingOrders see [UserPermissions.canMakeReadingOrders]; null leaves it unchanged.
 */
@Serializable
data class UserPermissionsPatch(
    @SerialName("canEdit") val canEditMetadata: Boolean? = null,
    @SerialName("canCurateLibrary") val canCurateLibrary: Boolean? = null,
    @SerialName("canContributeStoryWorld") val canContributeStoryWorld: Boolean? = null,
    @SerialName("canCurateStoryWorld") val canCurateStoryWorld: Boolean? = null,
    @SerialName("canMakeReadingOrders") val canMakeReadingOrders: Boolean? = null,
) {
    /** True when the patch names no flag at all. */
    val isEmpty: Boolean
        get() =
            canEditMetadata == null &&
                canCurateLibrary == null &&
                canContributeStoryWorld == null &&
                canCurateStoryWorld == null &&
                canMakeReadingOrders == null
}

/** This patch, also naming [permission] as [granted]. [Permission.UNKNOWN] is never sent, so it changes nothing. */
fun UserPermissionsPatch.granting(
    permission: Permission,
    granted: Boolean,
): UserPermissionsPatch =
    when (permission) {
        Permission.EDIT_METADATA -> copy(canEditMetadata = granted)
        Permission.CURATE_LIBRARY -> copy(canCurateLibrary = granted)
        Permission.CONTRIBUTE_STORY_WORLD -> copy(canContributeStoryWorld = granted)
        Permission.CURATE_STORY_WORLD -> copy(canCurateStoryWorld = granted)
        Permission.MAKE_READING_ORDERS -> copy(canMakeReadingOrders = granted)
        Permission.UNKNOWN -> this
    }

/** These flags with [patch]'s named fields applied; a null [patch] or field leaves the value as stored. */
fun UserPermissions.patchedBy(patch: UserPermissionsPatch?): UserPermissions =
    if (patch == null) {
        this
    } else {
        UserPermissions(
            canEditMetadata = patch.canEditMetadata ?: canEditMetadata,
            canCurateLibrary = patch.canCurateLibrary ?: canCurateLibrary,
            canContributeStoryWorld = patch.canContributeStoryWorld ?: canContributeStoryWorld,
            canCurateStoryWorld = patch.canCurateStoryWorld ?: canCurateStoryWorld,
            canMakeReadingOrders = patch.canMakeReadingOrders ?: canMakeReadingOrders,
        )
    }
