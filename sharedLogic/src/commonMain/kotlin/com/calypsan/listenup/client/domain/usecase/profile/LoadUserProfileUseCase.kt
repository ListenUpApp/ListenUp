package com.calypsan.listenup.client.domain.usecase.profile

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.UserProfile
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Loads a user's public profile.
 *
 * Retrieves the complete profile data including stats, recent activity,
 * and public shelves. Used for viewing other users' profiles.
 *
 * Usage:
 * ```kotlin
 * val result = loadUserProfileUseCase(userId = "user-123")
 * when (result) {
 *     is Success -> displayProfile(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class LoadUserProfileUseCase(
    private val profileRepository: ProfileRepository,
) {
    /**
     * Load a user's public profile.
     *
     * @param userId The user ID to fetch profile for
     * @return Result containing the user profile or a failure
     */
    open suspend operator fun invoke(userId: String): AppResult<UserProfile> {
        logger.debug { "Loading profile for user: $userId" }
        return profileRepository.getUserProfile(userId)
    }
}
