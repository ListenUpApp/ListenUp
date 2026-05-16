package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.core.Timestamp

/**
 * Marker interface for entities that support synchronization with the server.
 *
 * Implementing entities must track their sync state, modification time, and
 * server version for conflict detection during delta sync operations.
 */
interface Syncable {
    /**
     * Current synchronization state of this entity.
     * See [SyncState] for possible values.
     */
    val syncState: SyncState

    /**
     * Last modification timestamp.
     * Updated whenever local changes are made to the entity.
     * Type-safe wrapper around Unix epoch milliseconds.
     */
    val lastModified: Timestamp

    /**
     * Server-side version timestamp.
     * Null if entity has never been synced with server.
     * Used for conflict detection - if server version is newer than
     * our lastModified, a conflict exists.
     * Type-safe wrapper around Unix epoch milliseconds.
     */
    val serverVersion: Timestamp?
}

/**
 * Synchronization state for entities implementing [Syncable].
 *
 * State transitions:
 * - New entities start as NOT_SYNCED
 * - Local modifications transition SYNCED -> NOT_SYNCED
 * - Sync operations transition NOT_SYNCED -> SYNCING -> SYNCED
 * - Conflicts transition to CONFLICT when server has newer version
 */
enum class SyncState {
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
