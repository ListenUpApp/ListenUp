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
}
