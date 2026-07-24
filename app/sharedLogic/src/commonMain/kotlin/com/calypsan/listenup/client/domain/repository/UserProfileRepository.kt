package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.CachedUserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repository for accessing cached user profile data.
 *
 * Provides offline-first access to user profiles for ANY user — the signed-in user and everyone
 * else — backed by the server-synced `public_profiles` roster. Used primarily for displaying
 * avatars and profile info in social features.
 */
interface UserProfileRepository {
    /**
     * Get a user's cached profile by ID from the local cache.
     *
     * @param userId The user's unique ID
     * @return The cached profile or null if not found
     */
    suspend fun getById(userId: String): CachedUserProfile?

    /**
     * Observe a user's cached profile reactively.
     *
     * Emits the current cached value immediately, then emits again whenever
     * the profile is updated (e.g. via firehose profile.updated events or sync).
     * Emits null if the profile is not yet cached.
     *
     * @param userId The user's unique ID
     * @return Flow emitting the cached profile or null if not yet cached
     */
    fun observeProfile(userId: String): Flow<CachedUserProfile?>
}
