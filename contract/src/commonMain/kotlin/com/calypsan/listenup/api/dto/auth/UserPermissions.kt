package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-user action permissions, independent of [UserRole]. ROOT/ADMIN implicitly hold all
 * permissions; these flags grant capabilities to MEMBER users.
 *
 * **Adding a flag.** A new flag that *restricts* defaults to the permissive value; a new flag that
 * *grants a new power* defaults to `false`, so neither an old server's response nor an old admin
 * client can grant it by omission. Patches always go through [UserPermissionsPatch], whose null fields
 * mean unchanged.
 *
 * @property canEdit may edit book content metadata (title, genres, contributors, series).
 * @property canContributeStoryWorld may create and edit Story World entities and entries, and revert
 *   changes. **A deliberate exception to the rule above:** it grants a new power yet defaults to
 *   `true`, by product decision — contributing to Story World is a default member capability, guarded
 *   by edit history and undo rather than by permission. An admin can still switch it off per member.
 * @property canCurateStoryWorld may merge and delete Story World entities and entries. Grants a new
 *   power, so it defaults to `false`.
 */
@Serializable
data class UserPermissions(
    @SerialName("canEdit") val canEdit: Boolean = true,
    @SerialName("canContributeStoryWorld") val canContributeStoryWorld: Boolean = true,
    @SerialName("canCurateStoryWorld") val canCurateStoryWorld: Boolean = false,
)
