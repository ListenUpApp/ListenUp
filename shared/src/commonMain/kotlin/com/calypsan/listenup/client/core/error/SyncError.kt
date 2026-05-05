package com.calypsan.listenup.client.core.error

/**
 * Domain errors for library sync operations.
 *
 * These surface to the user via ErrorBus when sync fails in ways
 * that affect data freshness or integrity.
 *
 * Note: Many sync sub-operations (FTS rebuild, individual cover downloads,
 * SSE event processing) fail silently by design — they retry on next sync.
 * Only top-level sync failures and persistent connection issues use these.
 */
@Deprecated(
    message = "Migrate to `com.calypsan.listenup.api.error.SyncError`. Deleted in Task 16 of Phase 3.",
    level = DeprecationLevel.WARNING,
)
sealed interface SyncError : AppError {
    /**
     * Top-level sync operation failed.
     *
     * Emitted when the full pull sync fails (not individual pullers).
     * User should see this so they know their library may be stale.
     */
    data class SyncFailed(
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message = "Library sync failed. Pull down to retry."
        override val code = "SYNC_FAILED"
        override val isRetryable = true
    }

    /**
     * Real-time SSE connection lost.
     *
     * Emitted when SSE disconnects and reconnection fails.
     * User's library won't update in real-time until reconnected.
     */
    data class RealtimeDisconnected(
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message = "Lost connection to server. Changes may be delayed."
        override val code = "SYNC_REALTIME_DISCONNECTED"
        override val isRetryable = true
    }

    /**
     * Push sync failed — local changes couldn't be sent to server.
     *
     * More concerning than pull failures because the user's edits
     * (shelf changes, progress updates) aren't being persisted server-side.
     */
    data class PushFailed(
        override val debugInfo: String? = null,
    ) : SyncError {
        override val message = "Failed to sync your changes to server. Will retry automatically."
        override val code = "SYNC_PUSH_FAILED"
        override val isRetryable = true
    }
}
