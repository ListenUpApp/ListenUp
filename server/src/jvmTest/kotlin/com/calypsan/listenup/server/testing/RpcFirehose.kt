package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncStreamServiceImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Opens the RPC firehose directly over [bus] as the caller [principal] — the test-side
 * observation channel for "what does this subscriber see live". Delivery semantics (per-user
 * scoping, access gating, replay-then-live ordering, cursor-stale) are exactly production's:
 * this IS [SyncStreamServiceImpl], only the transport (WebSocket + auth wall) is absent —
 * covered by the client-side e2e harness and the RPC mount's own tests.
 *
 * Frames are unwrapped from their [RpcEvent.Data] envelope; a typed [RpcEvent.Error] fails the
 * test loudly rather than vanishing into an empty collection.
 */
internal fun rpcFirehose(
    bus: ChangeBus,
    principal: PrincipalProvider,
    bookAccessPolicy: () -> BookAccessPolicy = { error("BookAccessPolicy not wired for this test") },
    sinceRevision: Long? = null,
): Flow<SyncFrame> =
    SyncStreamServiceImpl(
        bus = bus,
        bookAccessPolicy = bookAccessPolicy,
        principal = principal,
    ).observeEvents(sinceRevision)
        .map { event ->
            when (event) {
                is RpcEvent.Data -> event.value
                is RpcEvent.Error -> error("firehose surfaced a typed error: ${event.error.code}")
                is RpcEvent.Complete -> error("unexpected explicit Complete marker on the firehose")
            }
        }

/** The stream's domain data frames only — hello/heartbeat/CursorStale CONTROL frames dropped. */
internal fun Flow<SyncFrame>.domainFrames(): Flow<SyncFrame> = filter { it.domain != SyncFrame.CONTROL }
