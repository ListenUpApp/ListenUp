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

    /**
     * Sent when the recipient's accessible set may have changed without a book row
     * itself mutating — e.g. a collection was shared/unshared with them, a share's
     * permission changed, or a book was released into a collection they can see.
     * The client treats this as "re-derive your accessible library" and re-pulls
     * the access-aware books digest. A bare signal is enough; no scope is carried.
     */
    @Serializable
    @SerialName("SyncControl.AccessChanged")
    data object AccessChanged : SyncControl

    /**
     * The authenticated user's account was deleted by an admin. The client clears auth (logout)
     * and may surface [reason]. Delivered per-user on the firehose control channel, published
     * before session revocation so the still-authed connection receives it.
     */
    @Serializable
    @SerialName("SyncControl.UserDeleted")
    data class UserDeleted(
        val reason: String? = null,
    ) : SyncControl

    /** Content-free broadcast nudge: active-session presence changed; re-fetch via SocialService. */
    @Serializable
    @SerialName("SyncControl.ActiveSessionsChanged")
    data object ActiveSessionsChanged : SyncControl

    /** Content-free broadcast nudge: a new activity was recorded; re-fetch via ActivityService. */
    @Serializable
    @SerialName("SyncControl.ActivityChanged")
    data object ActivityChanged : SyncControl

    /**
     * Content-free broadcast nudge: server-identity settings (display name / remote URL) changed.
     * Clients re-fetch [com.calypsan.listenup.api.InstanceService.getServerInfo] and silently update
     * their stored remote-URL fallback — so an admin's new domain reaches connected clients without a
     * cold start. No payload: the value travels on the existing getServerInfo probe (one source of truth).
     */
    @Serializable
    @SerialName("SyncControl.ServerInfoChanged")
    data object ServerInfoChanged : SyncControl
}
