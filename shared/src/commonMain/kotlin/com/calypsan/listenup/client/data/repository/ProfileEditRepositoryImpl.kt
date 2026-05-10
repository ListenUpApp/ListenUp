package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.IODispatcher
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ProfileApiContract
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private const val NO_CURRENT_USER_MESSAGE = "No current user"
private const val NO_CURRENT_USER_FOUND_MESSAGE = "No current user found"
private const val AUTO_VALUE = "auto"
private val logger = KotlinLogging.logger {}

/**
 * Repository for profile editing operations using offline-first pattern.
 *
 * Handles the edit flow:
 * 1. Apply optimistic update to local database
 * 2. Queue operation for server sync via PendingOperationRepository
 * 3. Return success immediately
 *
 * Server propagation returns when the profile sync domain migrates to the renovated engine.
 */
class ProfileEditRepositoryImpl(
    private val userDao: UserDao,
    private val profileApi: ProfileApiContract,
) : ProfileEditRepository {
    /**
     * Update the user's tagline.
     */
    override suspend fun updateTagline(tagline: String?): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Updating tagline (offline-first)" }

            // Get current user
            val user = userDao.getCurrentUser()
            if (user == null) {
                logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                return@withContext Failure(Exception(NO_CURRENT_USER_MESSAGE))
            }

            // Apply optimistic update
            userDao.updateTagline(
                userId = user.id.value,
                tagline = tagline,
                updatedAt = currentEpochMilliseconds(),
            )

            logger.info { "Tagline updated locally" }
            Success(Unit)
        }

    /**
     * Upload a new avatar image.
     *
     * Note: Unlike tagline updates, we do NOT apply an optimistic local update here.
     * The new avatar path isn't known until the server processes the upload.
     * The current avatar continues to display until sync completes and the
     * ProfileAvatarHandler updates UserEntity with the server response.
     */
    override suspend fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Uploading avatar (offline-first), size=${imageData.size}" }

            // Get current user
            val user = userDao.getCurrentUser()
            if (user == null) {
                logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                return@withContext Failure(Exception(NO_CURRENT_USER_MESSAGE))
            }

            // Do NOT update avatar locally - we don't have the server path yet.
            // The current avatar will continue to display until:
            // 1. ProfileAvatarHandler successfully uploads the image
            // 2. Handler updates UserEntity with the server's response (including the new path)

            logger.info { "Avatar upload skipped until profile sync domain migrates" }
            Success(Unit)
        }

    /**
     * Revert to auto-generated avatar.
     */
    override suspend fun revertToAutoAvatar(): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Reverting to auto avatar (offline-first)" }

            // Get current user
            val user = userDao.getCurrentUser()
            if (user == null) {
                logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                return@withContext Failure(Exception(NO_CURRENT_USER_MESSAGE))
            }

            // Apply optimistic update
            userDao.updateAvatar(
                userId = user.id.value,
                avatarType = AUTO_VALUE,
                avatarValue = null,
                avatarColor = user.avatarColor, // Keep existing color
                updatedAt = currentEpochMilliseconds(),
            )

            logger.info { "Reverted to auto avatar locally" }
            Success(Unit)
        }

    /**
     * Update the user's name.
     *
     * Applies optimistic update locally, then queues for server sync.
     */
    override suspend fun updateName(
        firstName: String,
        lastName: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Updating name (offline-first)" }

            // Get current user
            val user = userDao.getCurrentUser()
            if (user == null) {
                logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                return@withContext Failure(Exception(NO_CURRENT_USER_MESSAGE))
            }

            // Apply optimistic update - compute displayName locally
            val displayName = "$firstName $lastName".trim()
            userDao.updateName(
                userId = user.id.value,
                firstName = firstName,
                lastName = lastName,
                displayName = displayName,
                updatedAt = currentEpochMilliseconds(),
            )

            logger.info { "Name updated locally" }
            Success(Unit)
        }

    /**
     * Change the user's password.
     *
     * This is NOT an offline-first operation - requires immediate server confirmation.
     */
    override suspend fun changePassword(newPassword: String): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Changing password (requires server)" }

            // Password change requires immediate server confirmation
            when (
                val result =
                    profileApi.updateMyProfile(
                        newPassword = newPassword,
                    )
            ) {
                is Success -> {
                    logger.info { "Password changed successfully" }
                    Success(Unit)
                }

                is Failure -> {
                    logger.error { "Password change failed: ${result.message}" }
                    result
                }
            }
        }
}
