package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.flow.SharedFlow

/**
 * Engine-facing seam for the SSE client. Allows [SyncEngine] to be tested with
 * fakes without opening real network connections. Implemented by [SyncSseClient].
 */
interface SseClient {
    val frames: SharedFlow<ParsedSseFrame>

    fun seedLastEventId(initial: Long?)

    fun connect()

    fun disconnect()
}
