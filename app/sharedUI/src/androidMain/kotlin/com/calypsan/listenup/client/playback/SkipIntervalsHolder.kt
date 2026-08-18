package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Milliseconds in a second — the only unit conversion this holder performs. */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * The skip amounts every platform playback surface reads, kept in step with the synced setting.
 *
 * Media3 asks for these from plain synchronous callbacks — `onCustomCommand`,
 * `createNotification`, `seekForward` — which can neither suspend nor block. So the
 * Room-backed preference is collected once onto the service scope and published here as plain
 * fields those callbacks read, exactly the shape
 * [com.calypsan.listenup.client.localization.SystemStringsHolder] uses for the same reason.
 *
 * Nothing here touches the network: [PlaybackPreferences] answers from the local mirror, so a
 * lapsed session or a dead server changes nothing about how far a skip moves.
 *
 * Before [follow] has delivered anything — a window of milliseconds, but a real one, because a
 * car can connect the instant the service starts — the stock intervals are served. A zero-second
 * skip would be a worse answer than the default one.
 *
 * Reads happen on Media3's binder threads and writes on the service scope, hence `@Volatile`.
 *
 * @param preferences The synced playback defaults to follow.
 */
class SkipIntervalsHolder(
    private val preferences: PlaybackPreferences,
) {
    /** The configured forward skip, in seconds. */
    @Volatile
    var forwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC
        private set

    /** The configured backward skip, in seconds. */
    @Volatile
    var backwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC
        private set

    /** [forwardSec] in milliseconds, the unit every Media3 seek speaks. */
    val forwardMs: Long get() = forwardSec * MILLIS_PER_SECOND

    /** [backwardSec] in milliseconds. */
    val backwardMs: Long get() = backwardSec * MILLIS_PER_SECOND

    /**
     * Starts tracking the synced preference on [scope].
     *
     * Call once, from the owning service's scope — cancelling that scope stops the tracking.
     */
    fun follow(scope: CoroutineScope) {
        scope.launch { preferences.observeDefaultSkipForwardSec().collect { forwardSec = it } }
        scope.launch { preferences.observeDefaultSkipBackwardSec().collect { backwardSec = it } }
    }
}
