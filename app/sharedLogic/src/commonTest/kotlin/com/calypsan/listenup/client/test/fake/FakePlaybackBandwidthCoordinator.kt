package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.playback.PlaybackBandwidthCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [PlaybackBandwidthCoordinator] for tests: records every `setStreamingBuffering` value
 * and reflects it straight into [shouldYield]. Unlike the real `DefaultPlaybackBandwidthCoordinator`
 * it starts no `stateIn` collector and uses no real-time `delay`s, so it neither leaks a coroutine
 * onto an uncancelled test scope nor hangs `runTest`.
 */
class FakePlaybackBandwidthCoordinator : PlaybackBandwidthCoordinator {
    val calls = mutableListOf<Boolean>()

    private val yielding = MutableStateFlow(false)
    override val shouldYield: StateFlow<Boolean> = yielding.asStateFlow()

    override fun setStreamingBuffering(active: Boolean) {
        calls += active
        yielding.value = active
    }
}
