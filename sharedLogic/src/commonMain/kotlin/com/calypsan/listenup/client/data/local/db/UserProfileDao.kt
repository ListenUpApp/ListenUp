package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for cached user profiles.
 *
 * Stores profile data for any user (not just the current user) to enable
 * offline display of user avatars and names throughout the app.
 *
 * Profiles are cached from:
 * - Activity feed responses
 * - Discovery "currently listening" responses
 * - Book detail reader lists
 * - SSE profile.updated events
 */
@Dao
interface UserProfileDao {
    /**
     * Insert or update a user profile.
     */
    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    /**
     * Insert or update multiple user profiles.
     * Used for batch caching from API responses.
     */
    @Upsert
    suspend fun upsertAll(profiles: List<UserProfileEntity>)

    /**
     * Get a user profile by ID.
     * @return UserProfileEntity if cached, null otherwise
     */
    @Query("SELECT * FROM user_profiles WHERE id = :userId")
    suspend fun getById(userId: String): UserProfileEntity?

    /**
     * Get multiple user profiles by IDs.
     * Returns only profiles that are cached - missing IDs are not included.
     */
    @Query("SELECT * FROM user_profiles WHERE id IN (:userIds)")
    suspend fun getByIds(userIds: List<String>): List<UserProfileEntity>

    /**
     * Observe a user profile reactively.
     * Emits whenever the profile data changes.
     */
    @Query("SELECT * FROM user_profiles WHERE id = :userId")
    fun observeById(userId: String): Flow<UserProfileEntity?>

    /**
     * Observe multiple user profiles reactively.
     * Emits whenever any of the profiles change.
     */
    @Query("SELECT * FROM user_profiles WHERE id IN (:userIds)")
    fun observeByIds(userIds: List<String>): Flow<List<UserProfileEntity>>

    /**
     * Get all cached user profiles.
     * Useful for debugging or bulk operations.
     */
    @Query("SELECT * FROM user_profiles ORDER BY displayName")
    suspend fun getAll(): List<UserProfileEntity>

    /**
     * Delete a user profile from cache.
     */
    @Query("DELETE FROM user_profiles WHERE id = :userId")
    suspend fun delete(userId: String)

    /**
     * Clear all cached profiles.
     * Used during logout or data reset.
     */
    @Query("DELETE FROM user_profiles")
    suspend fun clear()

    /**
     * Update avatar fields for a cached profile.
     * Called when receiving SSE profile.updated events.
     */
    @Query(
        """
        UPDATE user_profiles
        SET avatarType = :avatarType,
            avatarValue = :avatarValue,
            avatarColor = :avatarColor,
            updatedAt = :updatedAt
        WHERE id = :userId
        """,
    )
    suspend fun updateAvatar(
        userId: String,
        avatarType: String,
        avatarValue: String?,
        avatarColor: String,
        updatedAt: Long,
    )

    /**
     * Update display name for a cached profile.
     */
    @Query(
        """
        UPDATE user_profiles
        SET displayName = :displayName,
            updatedAt = :updatedAt
        WHERE id = :userId
        """,
    )
    suspend fun updateDisplayName(
        userId: String,
        displayName: String,
        updatedAt: Long,
    )

    /**
     * Insert a profile only if it doesn't exist.
     * Used when we have partial data (no avatarType/avatarValue) and don't
     * want to overwrite existing complete profile data.
     *
     * @return Row ID if inserted, -1 if already exists
     */
    @Query(
        """
        INSERT OR IGNORE INTO user_profiles (id, displayName, avatarType, avatarValue, avatarColor, updatedAt)
        VALUES (:userId, :displayName, :avatarType, :avatarValue, :avatarColor, :updatedAt)
        """,
    )
    suspend fun insertIfNotExists(
        userId: String,
        displayName: String,
        avatarType: String,
        avatarValue: String?,
        avatarColor: String,
        updatedAt: Long,
    ): Long

    /**
     * Update only display name and avatar color for an existing profile.
     * Preserves existing avatarType/avatarValue (important for image avatars).
     * Used when API responses don't include full avatar data.
     */
    @Query(
        """
        UPDATE user_profiles
        SET displayName = :displayName,
            avatarColor = :avatarColor,
            updatedAt = :updatedAt
        WHERE id = :userId
        """,
    )
    suspend fun updatePartial(
        userId: String,
        displayName: String,
        avatarColor: String,
        updatedAt: Long,
    )
}
