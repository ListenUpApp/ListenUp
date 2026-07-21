package com.calypsan.listenup.api

import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * The cross-domain sync firehose — the only server-push stream for library data. Rides the same
 * WebSocket as every unary RPC (one socket, one connection-truth; SSE is retired).
 */
@Rpc
interface SyncStreamService {
    /**
     * Subscribe to the firehose, resuming after [sinceRevision] (null = from the retention
     * floor). The server emits an immediate control hello (a [SyncFrame] carrying
     * [SyncControl.Heartbeat]) so subscribers can latch "connected" on stream-open, then a
     * heartbeat every 25s; a behind-floor cursor gets [SyncControl.CursorStale] and completion.
     */
    fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>>
}
