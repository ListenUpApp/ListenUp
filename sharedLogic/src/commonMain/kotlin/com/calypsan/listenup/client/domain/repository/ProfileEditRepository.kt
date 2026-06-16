package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.result.AppResult

/**
 * Repository contract for profile editing operations.
 *
 * Provides a single [updateProfile] for all text-field changes (name, tagline, password)
 * and separate methods for avatar binary transport ([uploadAvatar], [revertToAutoAvatar]).
 *
 * Implementations apply changes via the [com.calypsan.listenup.api.ProfileService] RPC and
 * update the local Room cache on success so the UI reflects changes immediately.
 */
interface ProfileEditRepository {
    /**
     * Persist all changed profile text fields in one RPC call.
     *
     * Only non-null arguments are sent to the server; null means "no change for this field."
     * On success, the local Room cache is updated for any changed field so the UI
     * reflects the change immediately without waiting for the next sync.
     *
     * @param firstName The user's first name, or null to leave unchanged.
     * @param lastName The user's last name, or null to leave unchanged.
     * @param tagline The user's tagline (empty string clears it), or null to leave unchanged.
     * @param password A [PasswordChange] carrying the current and new passwords, or null to
     *   skip the password change.
     * @return [AppResult.Success] on success, or a typed [AppResult.Failure].
     */
    suspend fun updateProfile(
        firstName: String?,
        lastName: String?,
        tagline: String?,
        password: PasswordChange?,
    ): AppResult<Unit>

    /**
     * Upload a new avatar image via multipart REST POST.
     *
     * Sets the local avatar type to `"image"` on success so the UI immediately
     * switches to the uploaded-image render path.
     *
     * @param imageData The compressed image bytes.
     * @param contentType The MIME type of the image (e.g., `"image/jpeg"`).
     * @return [AppResult.Success] on success, or a typed [AppResult.Failure].
     */
    suspend fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit>

    /**
     * Revert to the auto-generated avatar.
     *
     * Sends an RPC update that sets `avatarType = "auto"` on the server and mirrors
     * the change locally so the UI immediately switches to the initials render path.
     *
     * @return [AppResult.Success] on success, or a typed [AppResult.Failure].
     */
    suspend fun revertToAutoAvatar(): AppResult<Unit>
}
