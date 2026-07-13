package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.UserPreferencesDao
import com.calypsan.listenup.client.data.local.db.UserPreferencesEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val DEFAULTS =
    UserPreferences(
        defaultPlaybackSpeed = 1.0f,
        defaultSkipForwardSec = 30,
        defaultSkipBackwardSec = 10,
        defaultSleepTimerMin = null,
        shakeToResetSleepTimer = false,
    )

/**
 * Offline-first [UserPreferencesRepository] backed by Room (the read source) and the
 * [com.calypsan.listenup.api.UserPreferencesService] RPC proxy (the write/refresh source).
 *
 * - [observePreferences] reads the cached row reactively, so the UI works offline and a cross-device
 *   change lands live once a [com.calypsan.listenup.api.sync.SyncControl.PreferencesChanged] nudge
 *   re-pulls it.
 * - [getPreferences] fetches from the server and writes through to Room (authoritative refresh).
 * - The setters write Room optimistically (instant UI) and enqueue the single-field PATCH through
 *   [OfflineEditor] — so a change made offline persists and replays on reconnect rather than being
 *   lost. Re-applying identical values is a no-op, so a firehose echo (via [getPreferences]) never
 *   flickers.
 *
 * All fallible suspend functions return [AppResult]; a thrown RPC transport error is folded into a
 * typed [AppResult.Failure] (the data layer never throws; cancellation is always re-raised).
 */
internal class UserPreferencesRepositoryImpl(
    private val channel: RpcChannel<UserPreferencesService>,
    private val dao: UserPreferencesDao,
    private val authSession: AuthSession,
    private val offlineEditor: OfflineEditor,
) : UserPreferencesRepository {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observePreferences(): Flow<UserPreferences> =
        authSession.authState
            .map { authSession.getUserId() }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                if (userId == null) {
                    flowOf(DEFAULTS)
                } else {
                    dao.observe(userId).map { it?.toDomain() ?: DEFAULTS }
                }
            }

    override suspend fun getPreferences(): AppResult<UserPreferences> =
        channel
            .call(idempotent = true) { it.getMyPreferences() }
            .map { it.toDomain() }
            .also { result -> if (result is AppResult.Success) cache(result.data) }

    override suspend fun setDefaultPlaybackSpeed(speed: Float): AppResult<Unit> =
        optimisticUpdate(
            patch = UpdateUserPreferencesRequest(defaultPlaybackSpeed = speed),
        ) { it.copy(defaultPlaybackSpeed = speed) }

    override suspend fun setDefaultSkipForwardSec(seconds: Int): AppResult<Unit> =
        optimisticUpdate(
            patch = UpdateUserPreferencesRequest(defaultSkipForwardSec = seconds),
        ) { it.copy(defaultSkipForwardSec = seconds) }

    override suspend fun setDefaultSkipBackwardSec(seconds: Int): AppResult<Unit> =
        optimisticUpdate(
            patch = UpdateUserPreferencesRequest(defaultSkipBackwardSec = seconds),
        ) { it.copy(defaultSkipBackwardSec = seconds) }

    override suspend fun setDefaultSleepTimerMin(minutes: Int?): AppResult<Unit> =
        optimisticUpdate(
            patch = UpdateUserPreferencesRequest(defaultSleepTimerMin = minutes),
        ) { it.copy(defaultSleepTimerMin = minutes) }

    override suspend fun setShakeToResetSleepTimer(enabled: Boolean): AppResult<Unit> =
        optimisticUpdate(
            patch = UpdateUserPreferencesRequest(shakeToResetSleepTimer = enabled),
        ) { it.copy(shakeToResetSleepTimer = enabled) }

    /**
     * Offline-first write: apply [mutate] to the cached row inside the outbox transaction and
     * enqueue [patch] for durable replay on reconnect. The server's authoritative merged result
     * arrives via the SSE firehose (the `PreferencesChanged` control nudge) and reconciles the cache — so a change
     * made offline is no longer lost, and no inline RPC is fired.
     */
    private suspend fun optimisticUpdate(
        patch: UpdateUserPreferencesRequest,
        mutate: (UserPreferences) -> UserPreferences,
    ): AppResult<Unit> {
        val userId =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        return offlineEditor.edit(OutboxChannels.Preferences, userId, patch) {
            cache(mutate(cachedOrDefaults()))
        }
    }

    /** The current cached preferences, or [DEFAULTS] when nothing is cached / no user is signed in. */
    private suspend fun cachedOrDefaults(): UserPreferences {
        val userId = authSession.getUserId() ?: return DEFAULTS
        return dao.get(userId)?.toDomain() ?: DEFAULTS
    }

    /**
     * Write [preferences] through to Room, keyed by the signed-in user. Idempotent: Room suppresses a
     * write that leaves the row unchanged, so [observePreferences] does not re-emit on an echo.
     */
    private suspend fun cache(preferences: UserPreferences) {
        val userId = authSession.getUserId() ?: return
        dao.upsert(preferences.toEntity(userId))
    }

    private fun UserPreferencesDto.toDomain(): UserPreferences =
        UserPreferences(
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            defaultSkipForwardSec = defaultSkipForwardSec,
            defaultSkipBackwardSec = defaultSkipBackwardSec,
            defaultSleepTimerMin = defaultSleepTimerMin,
            shakeToResetSleepTimer = shakeToResetSleepTimer,
        )

    private fun UserPreferencesEntity.toDomain(): UserPreferences =
        UserPreferences(
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            defaultSkipForwardSec = defaultSkipForwardSec,
            defaultSkipBackwardSec = defaultSkipBackwardSec,
            defaultSleepTimerMin = defaultSleepTimerMin,
            shakeToResetSleepTimer = shakeToResetSleepTimer,
        )

    private fun UserPreferences.toEntity(userId: String): UserPreferencesEntity =
        UserPreferencesEntity(
            id = userId,
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            defaultSkipForwardSec = defaultSkipForwardSec,
            defaultSkipBackwardSec = defaultSkipBackwardSec,
            defaultSleepTimerMin = defaultSleepTimerMin,
            shakeToResetSleepTimer = shakeToResetSleepTimer,
        )
}
