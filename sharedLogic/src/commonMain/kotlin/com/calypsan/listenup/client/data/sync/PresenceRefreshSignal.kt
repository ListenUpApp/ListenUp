package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Pings whenever active-session presence may have changed — on the server's broadcast
 * `ActiveSessionsChanged` nudge or on firehose reconnect. The social repositories
 * (currently-listening, book-readers) re-fetch their ACL-filtered RPC on each ping.
 */
class PresenceRefreshSignal {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Hot stream of presence-changed pings. */
    val signal: SharedFlow<Unit> = flow.asSharedFlow()

    /** Emit a presence-changed ping (non-suspending, drops if no buffer — a missed ping is harmless). */
    fun ping() {
        flow.tryEmit(Unit)
    }
}
