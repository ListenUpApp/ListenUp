package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Partial admin update of a user; null fields — including each permission flag — are left unchanged. */
@Serializable
data class AdminUserPatch(
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("role") val role: UserRole? = null,
    @SerialName("permissions") val permissions: UserPermissionsPatch? = null,
) {
    init {
        displayName?.let {
            require(it.isNotBlank() && it.length <= UpdateProfileRequest.MAX_DISPLAY_NAME) { "invalid displayName" }
        }
    }
}
