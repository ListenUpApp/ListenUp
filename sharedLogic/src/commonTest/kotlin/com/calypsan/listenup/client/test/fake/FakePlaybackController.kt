package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fake of [PlaybackController]. Records all method invocations for verification.
 * Reused by E2 VM tests.
 */
class FakePlaybackController(
    initialReady: Boolean = true,
) : PlaybackController {
    private val _isReady = MutableStateFlow(initialReady)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    var acquireCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var playCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    private val _seekCalls: MutableList<Long> = mutableListOf()
    val seekCalls: List<Long> get() = _seekCalls.toList()

    private val _speedCalls: MutableList<Float> = mutableListOf()
    val speedCalls: List<Float> get() = _speedCalls.toList()

    private val _volumeCalls: MutableList<Float> = mutableListOf()
    val volumeCalls: List<Float> get() = _volumeCalls.toList()

    private val _setMediaQueueCalls: MutableList<Pair<List<PlaybackMediaItem>, Long>> = mutableListOf()
    val setMediaQueueCalls: List<Pair<List<PlaybackMediaItem>, Long>> get() = _setMediaQueueCalls.toList()

    private val _startPlaybackCalls: MutableList<PlaybackManager.PrepareResult> = mutableListOf()
    val startPlaybackCalls: List<PlaybackManager.PrepareResult> get() = _startPlaybackCalls.toList()

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
    }

    override fun play() {
        playCount++
    }

    override fun pause() {
        pauseCount++
    }

    override fun stop() {
        stopCount++
    }

    override fun setVolume(volume: Float) {
        _volumeCalls += volume
    }

    override fun seekTo(positionMs: Long) {
        _seekCalls += positionMs
    }

    override fun setPlaybackSpeed(speed: Float) {
        _speedCalls += speed
    }

    override suspend fun setMediaQueue(
        items: List<PlaybackMediaItem>,
        startPositionMs: Long,
    ) {
        _setMediaQueueCalls += (items to startPositionMs)
    }

    override suspend fun startPlayback(prepareResult: PlaybackManager.PrepareResult) {
        _startPlaybackCalls += prepareResult
    }

    /** Test helper: drive `isReady` from outside to simulate connection state changes. */
    fun setReady(ready: Boolean) {
        _isReady.value = ready
    }
}
