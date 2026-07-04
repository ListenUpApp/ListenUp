package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * The slow-poll interval that backstops presence recovery. It is the airtight-recovery net for an
 * `ActiveSessionsChanged` nudge dropped under the control channel's `DROP_OLDEST` burst policy: while a
 * presence surface stays subscribed, [refreshTriggers] re-fetches at least this often, converging any
 * stale roster left by a lost nudge. Deliberately slow — the ping is the fast path, this is only the
 * bounded, subscription-scoped safety net.
 */
internal const val PRESENCE_POLL_INTERVAL_MS = 60_000L

/**
 * Pings whenever active-session presence may have changed — on the server's broadcast
 * `ActiveSessionsChanged` nudge or on firehose reconnect. The social repositories
 * (currently-listening, book-readers) re-fetch their ACL-filtered RPC on each ping.
 */
internal class PresenceRefreshSignal {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Hot stream of presence-changed pings. */
    val signal: SharedFlow<Unit> = flow.asSharedFlow()

    /** Emit a presence-changed ping (non-suspending, drops if no buffer — a missed ping is harmless). */
    fun ping() {
        flow.tryEmit(Unit)
    }
}

/**
 * Cold trigger stream for a presence-mirror refresh, unifying all three recovery layers into one flow:
 * it fires immediately on subscribe (fetch-on-subscribe), on every [PresenceRefreshSignal.signal] ping
 * (the fast path — the server's `ActiveSessionsChanged` nudge or a firehose reconnect), and on a slow
 * [PRESENCE_POLL_INTERVAL_MS] backstop tick for as long as the surface stays open.
 *
 * The poll closes the one hole the ping and lifecycle-reconcile paths leave open: a client that is
 * connected with the presence surface already subscribed, whose `ActiveSessionsChanged` nudge was
 * dropped under the control channel's `DROP_OLDEST` burst policy. No lifecycle edge fires and
 * fetch-on-subscribe has already run, so nothing else re-fetches — the poll converges that stale roster
 * within one interval. It is bounded and subscription-scoped: the ticker lives inside this cold flow, so
 * it runs only while a collector is present and costs nothing when the surface is closed.
 */
internal fun PresenceRefreshSignal.refreshTriggers(): Flow<Unit> =
    merge(signal, presencePollTicks()).onStart { emit(Unit) }

/** Emits every [PRESENCE_POLL_INTERVAL_MS], delaying first — the subscription-scoped backstop tick. */
private fun presencePollTicks(): Flow<Unit> =
    flow {
        while (true) {
            delay(PRESENCE_POLL_INTERVAL_MS)
            emit(Unit)
        }
    }
