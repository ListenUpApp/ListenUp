package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory fake of [UserPreferencesRepository] — the server-synced, Room-backed store the
 * Settings screen displays and the player resolves its defaults from.
 *
 * [observePreferences] is backed by a [MutableStateFlow] seeded with [initial], so a test can
 * model "this device has synced 2x as the default" without a database or a server. Setting
 * [failGetPreferences] makes [getPreferences] fail the way a dead session or an unreachable
 * server does, while the cached value keeps serving reads — the offline-first contract.
 */
class FakeUserPreferencesRepository(
    initial: UserPreferences = DEFAULTS,
) : UserPreferencesRepository {
    private val cached = MutableStateFlow(initial)

    /** When non-null, [getPreferences] fails with it; the cached value still serves reads. */
    var failGetPreferences: AppError? = null

    /** Number of [getPreferences] calls — asserts a read path did not reach for the network. */
    var getPreferencesCalls: Int = 0
        private set

    override fun observePreferences(): Flow<UserPreferences> = cached

    override suspend fun getPreferences(): AppResult<UserPreferences> {
        getPreferencesCalls++
        return failGetPreferences?.let { AppResult.Failure(it) } ?: AppResult.Success(cached.value)
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Float): AppResult<Unit> =
        update { it.copy(defaultPlaybackSpeed = speed) }

    override suspend fun setDefaultVolumeBoostDb(boostDb: Float): AppResult<Unit> =
        update { it.copy(defaultVolumeBoostDb = boostDb) }

    override suspend fun setDefaultSkipForwardSec(seconds: Int): AppResult<Unit> =
        update { it.copy(defaultSkipForwardSec = seconds) }

    override suspend fun setDefaultSkipBackwardSec(seconds: Int): AppResult<Unit> =
        update { it.copy(defaultSkipBackwardSec = seconds) }

    override suspend fun setDefaultSleepTimerMin(minutes: Int?): AppResult<Unit> =
        update { it.copy(defaultSleepTimerMin = minutes) }

    private fun update(mutate: (UserPreferences) -> UserPreferences): AppResult<Unit> {
        cached.value = mutate(cached.value)
        return AppResult.Success(Unit)
    }

    companion object {
        /** The stock preferences a brand-new account starts from. */
        val DEFAULTS =
            UserPreferences(
                defaultPlaybackSpeed = 1.0f,
                defaultVolumeBoostDb = 0.0f,
                defaultSkipForwardSec = 30,
                defaultSkipBackwardSec = 10,
                defaultSleepTimerMin = null,
            )
    }
}
