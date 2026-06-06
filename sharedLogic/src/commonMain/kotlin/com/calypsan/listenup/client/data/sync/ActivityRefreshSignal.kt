package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Pings whenever a new activity may have been recorded — on the server's broadcast
 * `ActivityChanged` nudge or on firehose reconnect. The activity-feed repository re-fetches
 * the feed head on each ping.
 */
class ActivityRefreshSignal {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Hot stream of activity-changed pings. */
    val signal: SharedFlow<Unit> = flow.asSharedFlow()

    /** Emit an activity-changed ping (non-suspending, drops if no buffer — a missed ping is harmless). */
    fun ping() {
        flow.tryEmit(Unit)
    }
}
