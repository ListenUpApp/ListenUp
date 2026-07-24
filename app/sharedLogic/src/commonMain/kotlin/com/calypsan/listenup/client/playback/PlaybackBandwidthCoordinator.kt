package com.calypsan.listenup.client.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-platform "playback preempts downloads" signal. The active player reports when a
 * **streaming** (not-fully-downloaded) book is actively buffering; background downloads observe
 * [shouldYield] and back off so the stall clears fast — then resume once playback is flowing.
 *
 * The yield is tied to *buffering*, not merely *playing*: downloads get out of the way during the
 * stalls that actually matter and make progress the rest of the time, so they're never starved
 * (and the streamed book's own auto-download eventually completes → future playback is local).
 *
 * A single commonMain `single`; both the iOS [com.calypsan.listenup.client] native `PlayerCoordinator`
 * (via Swift Export) and the Android/Desktop `PlaybackManager` feed it, and both download paths
 * (the shared byte pump on Android, `NSURLSession` task suspension on iOS) obey it.
 */
interface PlaybackBandwidthCoordinator {
    /** True while a not-fully-downloaded book is buffering — background downloads should yield. */
    val shouldYield: StateFlow<Boolean>

    /** Called by the active player when its streaming-buffering state changes. Idempotent. */
    fun setStreamingBuffering(active: Boolean)
}

/**
 * Default [PlaybackBandwidthCoordinator] with **instant acquire, delayed release** hysteresis and a
 * hard **[maxYield] cap**:
 * - yielding turns on the instant buffering starts (playback needs bandwidth *now*);
 * - it turns off after [releaseDelay] of non-buffering — so a stream that flickers
 *   buffering→playing→buffering doesn't thrash downloads' connections;
 * - it turns off unconditionally after [maxYield] of *continuous* buffering, so a stuck/dead stream
 *   (or a producer that never leaves `.buffering`) can never starve downloads.
 *
 * All timing lives in a **single** collector on [scope] (`transformLatest` cancels the prior branch
 * on each new value), so `setStreamingBuffering` only writes a `StateFlow` — there is no cross-thread
 * mutation to race, and acquire deterministically wins over an in-flight release. This is the fix for
 * the lock-free release race the naive `launch`/`cancel` version had.
 *
 * [scope] should be app-lifetime (the download/playback scope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultPlaybackBandwidthCoordinator(
    scope: CoroutineScope,
    releaseDelay: Duration = 2.seconds,
    maxYield: Duration = 60.seconds,
) : PlaybackBandwidthCoordinator {
    private val buffering = MutableStateFlow(false)

    override val shouldYield: StateFlow<Boolean> =
        buffering
            .transformLatest { active ->
                if (active) {
                    emit(true) // instant acquire
                    delay(maxYield) // cap: never starve downloads on a stuck/endless buffer
                    emit(false)
                } else {
                    delay(releaseDelay) // delayed release; a re-buffer cancels this branch
                    emit(false)
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setStreamingBuffering(active: Boolean) {
        buffering.value = active
    }
}
