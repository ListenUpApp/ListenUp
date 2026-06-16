package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Backs [UserPreferencesRepository] with the [com.calypsan.listenup.api.UserPreferencesService] RPC
 * proxy (issue #599 — replaces the deleted REST `UserPreferencesApi`).
 *
 * Reads map the wire [UserPreferencesDto] to the domain [UserPreferences]; writes forward a single
 * non-null field via PATCH semantics. RPC calls are wrapped so a thrown transport error becomes a
 * typed [AppResult.Failure] — the data layer never throws (cancellation is always re-raised).
 */
class UserPreferencesRepositoryImpl(
    private val rpcFactory: UserPreferencesRpcFactory,
) : UserPreferencesRepository {
    override suspend fun getPreferences(): AppResult<UserPreferences> =
        rpcCall { rpcFactory.get().getMyPreferences() }.map { it.toDomain() }

    override suspend fun setDefaultPlaybackSpeed(speed: Float): AppResult<Unit> =
        update(UpdateUserPreferencesRequest(defaultPlaybackSpeed = speed))

    override suspend fun setDefaultSkipForwardSec(seconds: Int): AppResult<Unit> =
        update(UpdateUserPreferencesRequest(defaultSkipForwardSec = seconds))

    override suspend fun setDefaultSkipBackwardSec(seconds: Int): AppResult<Unit> =
        update(UpdateUserPreferencesRequest(defaultSkipBackwardSec = seconds))

    override suspend fun setDefaultSleepTimerMin(minutes: Int?): AppResult<Unit> =
        update(UpdateUserPreferencesRequest(defaultSleepTimerMin = minutes))

    override suspend fun setShakeToResetSleepTimer(enabled: Boolean): AppResult<Unit> =
        update(UpdateUserPreferencesRequest(shakeToResetSleepTimer = enabled))

    private suspend fun update(request: UpdateUserPreferencesRequest): AppResult<Unit> =
        rpcCall { rpcFactory.get().updateMyPreferences(request) }.toUnit()

    /**
     * Run an RPC call, folding any thrown transport error into an [AppResult.Failure] via
     * [ErrorMapper]. Re-throws [CancellationException] so structured concurrency is preserved.
     */
    private suspend fun <T> rpcCall(block: suspend () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "User-preferences RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    /** Discard the typed data from a successful result, preserving failures. */
    private fun <T> AppResult<T>.toUnit(): AppResult<Unit> =
        when (this) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> this
        }

    private fun UserPreferencesDto.toDomain(): UserPreferences =
        UserPreferences(
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            defaultSkipForwardSec = defaultSkipForwardSec,
            defaultSkipBackwardSec = defaultSkipBackwardSec,
            defaultSleepTimerMin = defaultSleepTimerMin,
            shakeToResetSleepTimer = shakeToResetSleepTimer,
        )
}
