package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [UserPreferencesEntity] — the local cache of the user's synced
 * playback preferences.
 *
 * One row per user (keyed by `id` = user ID). The UI observes [observe] so a cross-device
 * change lands reactively; [upsert] writes the row through after a server fetch.
 */
@Dao
internal interface UserPreferencesDao {
    /**
     * Reactively observe the cached preferences for [userId].
     *
     * Emits the current row on first collect, then re-emits whenever [upsert] writes a
     * *different* row for this user. Room suppresses no-op writes that leave the row
     * unchanged, so re-applying identical values does not re-emit (no flicker on self-echo).
     */
    @Query("SELECT * FROM user_preferences WHERE id = :userId LIMIT 1")
    fun observe(userId: String): Flow<UserPreferencesEntity?>

    /** One-shot read of the cached preferences for [userId], or null when none are cached yet. */
    @Query("SELECT * FROM user_preferences WHERE id = :userId LIMIT 1")
    suspend fun get(userId: String): UserPreferencesEntity?

    /** Insert or update the user's cached preferences. */
    @Upsert
    suspend fun upsert(preferences: UserPreferencesEntity)

    /** Clear all cached preferences. Used on full re-sync / logout. */
    @Query("DELETE FROM user_preferences")
    suspend fun deleteAll()
}
