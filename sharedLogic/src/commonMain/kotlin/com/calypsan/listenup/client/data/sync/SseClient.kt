package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.flow.SharedFlow

/**
 * Engine-facing seam for the SSE client. Allows [SyncEngine] to be tested with
 * fakes without opening real network connections. Implemented by [SyncSseClient].
 */
internal interface SseClient {
    val frames: SharedFlow<ParsedSseFrame>

    fun seedLastEventId(initial: Long?)

    fun connect()

    fun disconnect()

    /**
     * Current `Last-Event-Id` the SSE client will resume from on the next
     * reconnect. Read-only; visibility lets [SyncEngine.handleCursorStale]
     * observe the cursor and tests assert reconnect behavior.
     */
    fun currentLastEventId(): Long?

    /**
     * Drop any active connection and reseed `lastEventId` from [newLastEventId].
     * Called by [SyncEngine.handleCursorStale] after a recovery catch-up to
     * ensure the next [connect] resumes from the new authoritative cursor
     * rather than looping forever with the pre-stale value.
     */
    suspend fun reseed(newLastEventId: Long?)

    /**
     * Reset the reconnect backoff to zero and wake the retry loop so the next
     * connect attempt fires immediately. Called by the reconnection supervisor
     * once it has confirmed the server is live again (possibly at a new URL),
     * turning "recover within up to 60s" into "recover within seconds".
     */
    fun reconnectNow()
}
