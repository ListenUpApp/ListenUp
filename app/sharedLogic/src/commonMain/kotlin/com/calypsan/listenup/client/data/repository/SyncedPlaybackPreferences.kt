package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [PlaybackPreferences] as a projection of the server-synced [UserPreferencesRepository] — the
 * one store that holds the user's playback defaults.
 *
 * Every read here is the same Room read the Settings screen makes, so the player and the
 * screen can no longer disagree: the defaults the user sees are the defaults a book without a
 * per-book override plays at. Nothing touches the network, so a lapsed session or a dead
 * server changes nothing about what the player resolves.
 */
internal class SyncedPlaybackPreferences(
    private val userPreferences: UserPreferencesRepository,
) : PlaybackPreferences {
    override fun observeDefaultPlaybackSpeed(): Flow<Float> = observe { it.defaultPlaybackSpeed }

    override suspend fun getDefaultPlaybackSpeed(): Float = current().defaultPlaybackSpeed

    override fun observeDefaultVolumeBoostDb(): Flow<Float> = observe { it.defaultVolumeBoostDb }

    override suspend fun getDefaultVolumeBoostDb(): Float = current().defaultVolumeBoostDb

    override fun observeDefaultSkipForwardSec(): Flow<Int> = observe { it.defaultSkipForwardSec }

    override fun observeDefaultSkipBackwardSec(): Flow<Int> = observe { it.defaultSkipBackwardSec }

    override suspend fun getDefaultSkipForwardSec(): Int = current().defaultSkipForwardSec

    override suspend fun getDefaultSkipBackwardSec(): Int = current().defaultSkipBackwardSec

    /** One field of the synced preferences, re-emitted only when that field actually moves. */
    private fun <T> observe(select: (UserPreferences) -> T): Flow<T> =
        userPreferences.observePreferences().map(select).distinctUntilChanged()

    /**
     * The currently cached preferences. `first()` on the Room-backed flow completes as soon as
     * the cached row (or the stock defaults) is available — a local read, never a request.
     */
    private suspend fun current(): UserPreferences = userPreferences.observePreferences().first()
}
