package com.calypsan.listenup.client.playback

/**
 * Tail-combined screen state. The VM exposes a single `screenState: StateFlow<NowPlayingScreenState>`
 * to the UI; combine produces this from independent flows of [state], [overlay], [isExpanded],
 * and the existing `sleepTimerManager.state`.
 *
 * Composes the rubric's "tail-combine" pattern: the expensive book/playback flow ([state])
 * combines with the cheap UI ephemera ([overlay], [isExpanded]) only at the screen-state
 * boundary, so overlay emissions don't re-execute the upstream pipeline.
 */
data class NowPlayingScreenState(
    val state: NowPlayingState,
    val overlay: NowPlayingOverlay,
    val isExpanded: Boolean,
    val sleepTimerState: SleepTimerState,
)
