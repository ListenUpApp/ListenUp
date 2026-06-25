@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult

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
    val shakeToResetSleepTimer: Boolean,
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
     * Get the current user's preferences from the server.
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

    /**
     * Update shake-to-reset sleep timer setting.
     *
     * @param enabled Whether shaking resets the sleep timer
     * @return Result indicating success or failure
     */
    suspend fun setShakeToResetSleepTimer(enabled: Boolean): AppResult<Unit>
}