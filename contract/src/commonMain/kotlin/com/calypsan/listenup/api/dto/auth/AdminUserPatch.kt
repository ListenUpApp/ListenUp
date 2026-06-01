package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Partial admin update of a user; null fields are left unchanged. */
@Serializable
data class AdminUserPatch(
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("role") val role: UserRole? = null,
    @SerialName("permissions") val permissions: UserPermissions? = null,
)
