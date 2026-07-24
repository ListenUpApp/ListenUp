package com.calypsan.listenup.client.data.local.db

/**
 * Synchronization state for local database entities.
 *
 * State transitions:
 * - New entities start as NOT_SYNCED
 * - Local modifications transition SYNCED -> NOT_SYNCED
 * - Sync operations transition NOT_SYNCED -> SYNCING -> SYNCED
 * - Conflicts transition to CONFLICT when server has newer version
 */
internal enum class SyncState {
    /**
     * Entity is clean and matches server state.
     * No pending local changes or server updates.
     */
    SYNCED,

    /**
     * Entity has local modifications not yet uploaded to server.
     * Will be included in next sync push operation.
     */
    NOT_SYNCED,

    /**
     * Upload operation is currently in progress for this entity.
     * Used to prevent duplicate uploads during concurrent sync attempts.
     */
    SYNCING,

    /**
     * Server has a newer version than our local modifications.
     * Requires conflict resolution (currently last-write-wins by timestamp).
     * Marked for user review in future versions.
     */
    CONFLICT,
}
