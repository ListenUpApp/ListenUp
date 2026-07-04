@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api.sync

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
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
@HiddenFromObjC
@Serializable
sealed interface SyncControl {
    /**
     * Sent when the client's `Last-Event-Id` is older than the bus's in-memory
     * replay window. Client must fall back to REST per-domain catch-up against
     * each registered domain.
     */
    @HiddenFromObjC
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
    @HiddenFromObjC
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
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.AccessChanged")
    data object AccessChanged : SyncControl

    /**
     * The authenticated user's account was deleted by an admin. The client clears auth (logout)
     * and may surface [reason]. Delivered per-user on the firehose control channel, published
     * before session revocation so the still-authed connection receives it.
     */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.UserDeleted")
    data class UserDeleted(
        val reason: String? = null,
    ) : SyncControl

    /** Content-free broadcast nudge: active-session presence changed; re-fetch via SocialService. */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.ActiveSessionsChanged")
    data object ActiveSessionsChanged : SyncControl

    // TODO(pre-v1.0 migration squash): remove — activities is a cursored mirror, nothing broadcasts this.

    /**
     * Legacy no-op. `activities` was promoted to a cursored Room mirror (#1028) — catch-up + the live
     * tail keep it current, so nothing broadcasts this and the dispatcher drops it. Kept only as a
     * sealed subtype so an older server's frame decodes cleanly (wire compatibility).
     */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.ActivityChanged")
    data object ActivityChanged : SyncControl

    /**
     * Content-free per-user nudge: the recipient's synced playback preferences changed on another
     * device. The client re-fetches [com.calypsan.listenup.api.UserPreferencesService.getMyPreferences]
     * and writes the result through to its local cache, so a change made on device A reaches device B
     * live instead of only on B's next Settings open.
     *
     * Delivered per-user via the firehose control channel ([ControlFrame] targeting): only the same
     * user's other devices receive it — never another user's. No payload: the value travels on the
     * existing getMyPreferences probe (one source of truth), mirroring [ServerInfoChanged].
     */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.PreferencesChanged")
    data object PreferencesChanged : SyncControl

    /**
     * Content-free broadcast nudge: server-identity settings (display name / remote URL) changed.
     * Clients re-fetch [com.calypsan.listenup.api.InstanceService.getServerInfo] and silently update
     * their stored remote-URL fallback — so an admin's new domain reaches connected clients without a
     * cold start. No payload: the value travels on the existing getServerInfo probe (one source of truth).
     */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.ServerInfoChanged")
    data object ServerInfoChanged : SyncControl

    /**
     * Content-free broadcast nudge: a bulk server-side write landed that was NOT published on the
     * live tail. The client re-derives every domain via digest reconciliation (the same pass the
     * sync engine runs on connect), re-pulling any domain whose digest diverged from the server's.
     *
     * Emitted by a firehose-suppressed bulk path — an Audiobookshelf import writes a burst of playback
     * positions and listening events under `FirehoseSuppressed`, so the rows commit and bump the
     * revision but never reach the lossy live tail. Without this nudge, other connected clients only
     * converge on their next reconnect (app restart); with it they reconcile live. The importing
     * admin's own client refreshes through the import flow independently.
     */
    @HiddenFromObjC
    @Serializable
    @SerialName("SyncControl.LibraryDataChanged")
    data object LibraryDataChanged : SyncControl
}
