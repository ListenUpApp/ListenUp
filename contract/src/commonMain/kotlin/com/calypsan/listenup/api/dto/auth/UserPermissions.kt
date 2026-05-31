package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-user action permissions, independent of [UserRole]. ROOT/ADMIN implicitly hold all
 * permissions; these flags grant capabilities to MEMBER users. Designed to grow — add a
 * field (defaulting to the permissive value) plus a column to extend the permission set.
 *
 * @property canEdit may edit book content metadata (title, genres, contributors, series).
 * @property canShare may create collection shares.
 */
@Serializable
data class UserPermissions(
    @SerialName("canEdit") val canEdit: Boolean = true,
    @SerialName("canShare") val canShare: Boolean = true,
)
