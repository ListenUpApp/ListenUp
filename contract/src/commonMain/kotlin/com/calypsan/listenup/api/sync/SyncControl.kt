package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.error.AppError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Out-of-band control events emitted on the SSE firehose, distinct from the
 * `SyncEvent.*` data events. Sent on a different SSE `event:` line so clients
 * can branch cleanly:
 *  - `event: control` → JSON-decoded [SyncControl]
 *  - `event: <domain>` → JSON-decoded [SyncEvent]
 *
 * Stable `@SerialName` discriminators — wire contract.
 */
@Serializable
sealed interface SyncControl {
    /**
     * Sent when the client's `Last-Event-Id` is older than the bus's in-memory
     * replay window. Client must fall back to REST per-domain catch-up against
     * each registered domain.
     */
    @Serializable
    @SerialName("SyncControl.CursorStale")
    data class CursorStale(
        val lastKnownRevision: Long,
    ) : SyncControl

    /**
     * Sent on a fatal error inside the SSE emit pipeline. Client treats this as
     * "connection ended; reconnect with current cursor and try again." The
     * [error] is the same typed [AppError] surface the rest of the contract
     * uses; stacktraces never cross the wire.
     */
    @Serializable
    @SerialName("SyncControl.StreamError")
    data class StreamError(
        val error: AppError,
    ) : SyncControl
}
