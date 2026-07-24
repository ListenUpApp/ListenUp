@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * User preferences that are synced to the server.
 *
 * These settings follow the user across devices.
 */
data class UserPreferences(
    val defaultPlaybackSpeed: Float,
    val defaultSkipForwardSec: Int,
    val defaultSkipBackwardSec: Int,
    val defaultSleepTimerMin: Int?,
)

/**
 * Repository contract for user preferences that sync to the server.
 *
 * Unlike local settings (theme, etc.), these preferences follow the
 * user across devices. Updates are synced optimistically.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface UserPreferencesRepository {
    /**
     * Reactively observe the current user's synced preferences from the local cache.
     *
     * Room is the single read source: the flow emits the cached row immediately (or sensible
     * defaults when nothing is cached yet), then re-emits whenever the cache changes — whether
     * from a local optimistic write, a server fetch, or a cross-device
     * [com.calypsan.listenup.api.sync.SyncControl.PreferencesChanged] nudge. Re-applying identical
     * values is a no-op, so an echo of the device's own change does not flicker the UI.
     *
     * Use this in ViewModels instead of a one-shot [getPreferences] so cross-device changes land
     * live and the screen works offline.
     */
    fun observePreferences(): Flow<UserPreferences>

    /**
     * Get the current user's preferences from the server, writing the result through to the local
     * cache (offline-first). On failure the cached value is preserved.
     *
     * @return Result containing preferences or failure
     */
    suspend fun getPreferences(): AppResult<UserPreferences>

    /**
     * Update default playback speed.
     *
     * @param speed Playback speed multiplier (e.g., 1.0, 1.5, 2.0)
     * @return Result indicating success or failure
     */
    suspend fun setDefaultPlaybackSpeed(speed: Float): AppResult<Unit>

    /**
     * Update default skip forward duration.
     *
     * @param seconds Skip duration in seconds
     * @return Result indicating success or failure
     */
    suspend fun setDefaultSkipForwardSec(seconds: Int): AppResult<Unit>

    /**
     * Update default skip backward duration.
     *
     * @param seconds Skip duration in seconds
     * @return Result indicating success or failure
     */
    suspend fun setDefaultSkipBackwardSec(seconds: Int): AppResult<Unit>

    /**
     * Update default sleep timer duration.
     *
     * @param minutes Sleep timer duration in minutes, or null to disable
     * @return Result indicating success or failure
     */
    suspend fun setDefaultSleepTimerMin(minutes: Int?): AppResult<Unit>
}
