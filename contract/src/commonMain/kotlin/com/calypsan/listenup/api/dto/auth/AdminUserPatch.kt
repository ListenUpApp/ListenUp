package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/** Partial admin update of a user; null fields are left unchanged. */
@Serializable
data class AdminUserPatch(
    val displayName: String? = null,
    val role: UserRole? = null,
    val permissions: UserPermissions? = null,
)
