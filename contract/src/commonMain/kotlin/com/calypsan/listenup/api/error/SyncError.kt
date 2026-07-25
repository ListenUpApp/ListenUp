package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for library sync operations.
 *
 * Many sync sub-operations (FTS rebuild, individual cover downloads, stream
 * event processing) fail silently by design — they retry on next sync. Only
 * top-level sync failures and persistent connection issues surface here.
 */
@Serializable
sealed interface SyncError : AppError {
    /** Top-level pull-sync failed. User's library may be stale. */
    @Serializable
    @SerialName("SyncError.SyncFailed")
    data class SyncFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "Library sync failed. Please try again."
        override val code: String = "SYNC_FAILED"
        override val isRetryable: Boolean = true
    }

    /** Real-time connection lost; reconnection failed. Live updates paused. */
    @Serializable
    @SerialName("SyncError.RealtimeDisconnected")
    data class RealtimeDisconnected(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "Lost connection to server. Changes may be delayed."
        override val code: String = "SYNC_REALTIME_DISCONNECTED"
        override val isRetryable: Boolean = true
    }

    /** Push sync failed — local edits not yet persisted server-side. */
    @Serializable
    @SerialName("SyncError.PushFailed")
    data class PushFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "Local changes could not be saved. They will retry shortly."
        override val code: String = "SYNC_PUSH_FAILED"
        override val isRetryable: Boolean = true
    }

    /**
     * Returned when an operation references a row that does not exist (e.g.
     * [softDelete][com.calypsan.listenup.server.sync.SyncableRepository.softDelete] of a
     * missing id). [domain] and [entityId] carry diagnostic context; the
     * user-facing [message] is generic.
     */
    @Serializable
    @SerialName("SyncError.NotFound")
    data class NotFound(
        val domain: String,
        val entityId: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "The requested item could not be found."
        override val code: String = "SYNC_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The requested sync domain is not registered on this server.
     *
     * Replaces the REST catch-up route's `404 "unknown domain: …"` prose body. Not retryable:
     * a client asking for a domain the server does not serve is a version-skew or wiring fault,
     * not a transient one — the client should skip that domain rather than re-poll it.
     */
    @Serializable
    @SerialName("SyncError.UnknownDomain")
    data class UnknownDomain(
        @SerialName("domain")
        val domain: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "This server does not provide that data."
        override val code: String = "SYNC_UNKNOWN_DOMAIN"
        override val isRetryable: Boolean = false
    }

    /**
     * A targeted fetch asked for more ids than the server will serve in one call.
     *
     * Replaces the REST route's `400 "too many ids (max N)"`. Not retryable as-sent: the caller
     * must chunk to [maxIds] and retry the chunks. Chunking — never truncating — matters,
     * because a silently truncated response reads to the client as "these ids are gone" and
     * would wrongly tombstone still-accessible rows.
     */
    @Serializable
    @SerialName("SyncError.TooManyIds")
    data class TooManyIds(
        @SerialName("requested")
        val requested: Int,
        @SerialName("maxIds")
        val maxIds: Int,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "Too much was requested at once."
        override val code: String = "SYNC_TOO_MANY_IDS"
        override val isRetryable: Boolean = false
    }

    /**
     * The domain does not support the requested targeted-match column.
     *
     * Replaces the REST route's `400 "bookIds fetch not supported for domain: …"`. The server
     * keeps a per-domain allowlist of which [com.calypsan.listenup.api.sync.TargetedMatch] cases
     * are sound; this is the typed refusal when one is asked for outside it.
     */
    @Serializable
    @SerialName("SyncError.UnsupportedMatch")
    data class UnsupportedMatch(
        @SerialName("domain")
        val domain: String,
        @SerialName("match")
        val match: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message: String = "That lookup is not available for this data."
        override val code: String = "SYNC_UNSUPPORTED_MATCH"
        override val isRetryable: Boolean = false
    }
}
