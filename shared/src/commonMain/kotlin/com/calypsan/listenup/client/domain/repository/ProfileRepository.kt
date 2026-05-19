package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.UserProfile

/**
 * Repository contract for user profile operations.
 *
 * Provides access to user profile data for viewing other users' profiles.
 * Own profile data is typically accessed via [UserRepository].
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ProfileRepository {
    /**
     * Get a user's public profile.
     *
     * @param userId The user ID to fetch profile for
     * @return Result containing the user profile or an error
     */
    suspend fun getUserProfile(userId: String): AppResult<UserProfile>
}
