package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fake of [AudioPlayer]. Records every call to [calls] for assertion-style
 * verification, and updates [state] / [positionMs] / [durationMs] so consumers that observe
 * the player behave as they would with a real engine.
 *
 * Used by playback seam tests — Bugs 3 (speed reverts) and 6 (position sync flakiness)
 * both need a player that records received calls and that can have state driven by the test.
 */
class FakePlayer : AudioPlayer {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _calls = mutableListOf<Call>()

    /** Ordered list of every call this player has received. */
    val calls: List<Call> get() = _calls.toList()

    override suspend fun load(segments: List<AudioSegment>) {
        _calls.add(Call.Load(segments))
        _durationMs.value = segments.sumOf { it.durationMs }
        _state.value = PlaybackState.Buffering
    }

    override fun play() {
        _calls.add(Call.Play)
        _state.value = PlaybackState.Playing
    }

    override fun pause() {
        _calls.add(Call.Pause)
        _state.value = PlaybackState.Paused
    }

    override fun seekTo(positionMs: Long) {
        _calls.add(Call.SeekTo(positionMs))
        _positionMs.value = positionMs
    }

    override fun setSpeed(speed: Float) {
        _calls.add(Call.SetSpeed(speed))
    }

    override fun release() {
        _calls.add(Call.Release)
        _state.value = PlaybackState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    /** Test helper: advance the reported position directly. */
    fun advancePosition(newPositionMs: Long) {
        _positionMs.value = newPositionMs
    }

    /** Test helper: drive playback state directly (e.g., simulate buffering → playing). */
    fun emitState(newState: PlaybackState) {
        _state.value = newState
    }

    sealed interface Call {
        data class Load(
            val segments: List<AudioSegment>,
        ) : Call

        data object Play : Call

        data object Pause : Call

        data class SeekTo(
            val positionMs: Long,
        ) : Call

        data class SetSpeed(
            val speed: Float,
        ) : Call

        data object Release : Call
    }
}
