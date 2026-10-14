package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PlaybackPreferences]; tests write the flows directly. */
class FakePlaybackPreferences(
    speed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    boostDb: Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB,
    forwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
    backwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
) : PlaybackPreferences {
    val defaultSpeed = MutableStateFlow(speed)
    val defaultBoostDb = MutableStateFlow(boostDb)
    val skipForwardSec = MutableStateFlow(forwardSec)
    val skipBackwardSec = MutableStateFlow(backwardSec)

    override fun observeDefaultPlaybackSpeed(): Flow<Float> = defaultSpeed

    override suspend fun getDefaultPlaybackSpeed(): Float = defaultSpeed.value

    override fun observeDefaultVolumeBoostDb(): Flow<Float> = defaultBoostDb

    override suspend fun getDefaultVolumeBoostDb(): Float = defaultBoostDb.value

    override fun observeDefaultSkipForwardSec(): Flow<Int> = skipForwardSec

    override fun observeDefaultSkipBackwardSec(): Flow<Int> = skipBackwardSec

    override suspend fun getDefaultSkipForwardSec(): Int = skipForwardSec.value

    override suspend fun getDefaultSkipBackwardSec(): Int = skipBackwardSec.value
}
