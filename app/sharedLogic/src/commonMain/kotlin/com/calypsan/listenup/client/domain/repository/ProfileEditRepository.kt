@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.result.AppResult

/**
 * Repository contract for profile editing operations.
 *
 * [updateProfile] splits by whether a password change is present: name/tagline changes are
 * offline-first (written to Room and queued for durable replay on reconnect); a password-bearing
 * change stays online (immediate server-side current-password validation, never queued). Avatar
 * binary transport ([uploadAvatar], [revertToAutoAvatar]) also stays online, unaffected by the
 * offline-first split.
 */
interface ProfileEditRepository {
    /**
     * Persist changed profile text fields.
     *
     * Only non-null arguments are sent to the server; null means "no change for this field."
     * When [password] is null, the change is offline-first: it writes the local Room cache
     * and enqueues a durable pending op, so the UI reflects the change immediately and the
     * edit survives being offline, replaying to the server on reconnect. When [password] is
     * non-null, the entire call — including any name/tagline change bundled with it — stays
     * fully synchronous online, since the server must validate the current password
     * immediately; it is never queued.
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
