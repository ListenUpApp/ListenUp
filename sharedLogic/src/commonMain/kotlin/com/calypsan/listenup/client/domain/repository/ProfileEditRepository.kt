package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult

/**
 * Repository contract for profile editing operations.
 *
 * Provides methods for modifying user profile data.
 * Uses offline-first pattern: changes are applied locally immediately
 * and queued for sync to server.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ProfileEditRepository {
    /**
     * Update the user's tagline.
     *
     * Applies update locally and queues for server sync.
     *
     * @param tagline The new tagline (or null to clear)
     * @return Result indicating success or failure
     */
    suspend fun updateTagline(tagline: String?): AppResult<Unit>

    /**
     * Upload a new avatar image.
     *
     * Stores the image data for offline sync and queues for server upload.
     * The avatar type is set to "image" locally with a pending indicator.
     *
     * @param imageData The compressed image bytes
     * @param contentType The MIME type of the image (e.g., "image/jpeg")
     * @return Result indicating success or failure
     */
    suspend fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit>

    /**
     * Revert to auto-generated avatar.
     *
     * Sets avatar type to "auto" locally and queues for server sync.
     *
     * @return Result indicating success or failure
     */
    suspend fun revertToAutoAvatar(): AppResult<Unit>

    /**
     * Update the user's name.
     *
     * Queues update for server sync. Server computes displayName from firstName + lastName.
     * Local cache is updated when sync completes.
     *
     * @param firstName The user's first name
     * @param lastName The user's last name
     * @return Result indicating success or failure
     */
    suspend fun updateName(
        firstName: String,
        lastName: String,
    ): AppResult<Unit>

    /**
     * Change the user's password.
     *
     * This is NOT an offline-first operation - requires immediate server confirmation.
     *
     * @param newPassword The new password to set
     * @return Result indicating success or failure
     */
    suspend fun changePassword(newPassword: String): AppResult<Unit>
}
