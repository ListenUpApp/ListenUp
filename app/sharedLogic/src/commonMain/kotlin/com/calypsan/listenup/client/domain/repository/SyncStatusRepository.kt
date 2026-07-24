package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.Timestamp

/**
 * Repository contract for sync status metadata.
 *
 * Provides access to sync timestamps and status information used for
 * delta sync operations and displaying sync state to users.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SyncStatusRepository {
    /**
     * Get the last successful library sync timestamp.
     *
     * Used for delta sync - only fetching books changed since this timestamp.
     * Returns null if the library has never been synced (triggers full sync).
     *
     * @return Type-safe Timestamp, or null if never synced
     */
    suspend fun getLastSyncTime(): Timestamp?

    /**
     * Set the last successful library sync timestamp.
     *
     * Called after a successful sync operation completes.
     *
     * @param timestamp Type-safe Timestamp value
     */
    suspend fun setLastSyncTime(timestamp: Timestamp)

    /**
     * Clear the last sync time (forces full resync).
     *
     * This forces the next sync to be a full sync (not delta),
     * re-fetching all books from the server. Useful when:
     * - Data migration requires re-population
     * - Database was corrupted or needs refresh
     * - User explicitly requests full resync
     */
    suspend fun clearLastSyncTime()
}
