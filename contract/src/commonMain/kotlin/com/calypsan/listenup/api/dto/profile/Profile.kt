package com.calypsan.listenup.api.dto.profile

import com.calypsan.listenup.api.dto.auth.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user's own profile. [avatarType] is `"auto"` (client-rendered initials) or `"image"` (uploaded);
 * [updatedAt] (unix millis, the `users.updated_at`) doubles as the avatar cache-bust token the client
 * appends to the `/api/v1/avatars/{id}` URL. Avatar color is NOT carried — it is client-derived.
 */
@Serializable
@SerialName("Profile")
data class Profile(
    val userId: UserId,
    val displayName: String,
    val tagline: String? = null,
    val avatarType: String = "auto",
    val updatedAt: Long,
)

/**
 * Response to a successful avatar upload (`POST /api/v1/profile/avatar`).
 *
 * [avatarUpdatedAt] is the server's epoch-ms stamp for the freshly stored bytes — the exact value
 * the `public_profiles` projection now carries. The client writes it verbatim into its own observed
 * row so the optimistic pre-sync render and the eventual sync echo agree on the avatar version,
 * busting the cached bitmap without a redundant re-download.
 */
@Serializable
@SerialName("AvatarUploadResponse")
data class AvatarUploadResponse(
    val avatarUpdatedAt: Long,
)

/** A password change: current password is verified before the new one is stored. */
@Serializable
@SerialName("PasswordChange")
data class PasswordChange(
    val currentPassword: String,
    val newPassword: String,
) {
    init {
        require(
            newPassword.length in MIN_PASSWORD..MAX_PASSWORD,
        ) { "newPassword length must be $MIN_PASSWORD..$MAX_PASSWORD" }
    }

    companion object {
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 1024
    }
}

/**
 * Update to the caller's own profile. Null fields are left unchanged. [password], when present,
 * triggers a current-password-gated change.
 */
@Serializable
@SerialName("UpdateProfileRequest")
data class UpdateProfileRequest(
    val displayName: String? = null,
    val tagline: String? = null,
    val avatarType: String? = null,
    val password: PasswordChange? = null,
) {
    init {
        displayName?.let { require(it.isNotBlank() && it.length <= MAX_DISPLAY_NAME) { "invalid displayName" } }
        tagline?.let { require(it.length <= MAX_TAGLINE) { "tagline too long" } }
        avatarType?.let { require(it == "auto" || it == "image") { "avatarType must be auto|image" } }
    }

    companion object {
        const val MAX_DISPLAY_NAME = 200
        const val MAX_TAGLINE = 500
    }
}
