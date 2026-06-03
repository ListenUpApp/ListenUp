package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesRequest
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Implementation of UserPreferencesRepository that wraps UserPreferencesApiContract.
 *
 * Maps data layer responses to domain types.
 *
 * @property userPreferencesApi Data layer API for user preferences operations
 */
class UserPreferencesRepositoryImpl(
    private val userPreferencesApi: UserPreferencesApiContract,
) : UserPreferencesRepository {
    override suspend fun getPreferences(): AppResult<UserPreferences> =
        when (val result = userPreferencesApi.getPreferences()) {
            is AppResult.Success -> {
                AppResult.Success(
                    UserPreferences(
                        defaultPlaybackSpeed = result.data.defaultPlaybackSpeed,
                        defaultSkipForwardSec = result.data.defaultSkipForwardSec,
                        defaultSkipBackwardSec = result.data.defaultSkipBackwardSec,
                        defaultSleepTimerMin = result.data.defaultSleepTimerMin,
                        shakeToResetSleepTimer = result.data.shakeToResetSleepTimer,
                    ),
                )
            }

            is AppResult.Failure -> {
                result
            }
        }

    override suspend fun setDefaultPlaybackSpeed(speed: Float): AppResult<Unit> =
        syncSetting(UserPreferencesRequest(defaultPlaybackSpeed = speed))

    override suspend fun setDefaultSkipForwardSec(seconds: Int): AppResult<Unit> =
        syncSetting(UserPreferencesRequest(defaultSkipForwardSec = seconds))

    override suspend fun setDefaultSkipBackwardSec(seconds: Int): AppResult<Unit> =
        syncSetting(UserPreferencesRequest(defaultSkipBackwardSec = seconds))

    override suspend fun setDefaultSleepTimerMin(minutes: Int?): AppResult<Unit> =
        syncSetting(UserPreferencesRequest(defaultSleepTimerMin = minutes))

    override suspend fun setShakeToResetSleepTimer(enabled: Boolean): AppResult<Unit> =
        syncSetting(UserPreferencesRequest(shakeToResetSleepTimer = enabled))

    private suspend fun syncSetting(request: UserPreferencesRequest): AppResult<Unit> =
        when (val result = userPreferencesApi.updatePreferences(request)) {
            is AppResult.Success -> {
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                logger.warn { "Failed to sync preference: ${result.message}" }
                result
            }
        }
}
