package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An admin's change to one user's [UserPermissions]: every field is nullable, and **null means
 * unchanged**. The server merges each field independently.
 *
 * Why not a whole [UserPermissions]: its fields default, so a patch that names one flag would decode
 * the rest as their defaults and silently grant or revoke them. That is exactly what an older admin
 * client sending `{"canEdit": false}` would do to a flag added after it shipped. Here an absent key is
 * `null`, and `null` leaves the stored value alone.
 *
 * Clients send only the flag being toggled, so an older server (which decodes `permissions` as a whole
 * [UserPermissions]) only ever sees `canEdit` from a `canEdit` toggle. Toggles for newer flags are shown
 * only when the server advertises them.
 *
 * @property canEdit see [UserPermissions.canEdit]; null leaves it unchanged.
 */
@Serializable
data class UserPermissionsPatch(
    @SerialName("canEdit") val canEdit: Boolean? = null,
)
