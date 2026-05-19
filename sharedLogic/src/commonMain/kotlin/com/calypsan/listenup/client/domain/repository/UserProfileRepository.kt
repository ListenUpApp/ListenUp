package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.CachedUserProfile

/**
 * Repository for accessing cached user profile data.
 *
 * Provides offline-first access to user profiles (other users, not current user).
 * Used primarily for displaying avatars and profile info in social features.
 */
interface UserProfileRepository {
    /**
     * Get a user's cached profile by ID from the local cache.
     *
     * @param userId The user's unique ID
     * @return The cached profile or null if not found
     */
    suspend fun getById(userId: String): CachedUserProfile?
}
