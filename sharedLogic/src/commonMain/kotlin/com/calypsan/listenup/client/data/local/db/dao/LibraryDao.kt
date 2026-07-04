package com.calypsan.listenup.client.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [LibraryEntity] operations.
 *
 * Live queries filter out tombstoned rows (`deletedAt IS NULL`) so the UI never
 * observes a deleted library. Diagnostic / sync paths that need tombstones use
 * [findAll].
 */
@Dao
internal interface LibraryDao {
    /**
     * Observe all live libraries reactively, ordered by name.
     *
     * Tombstoned rows (deletedAt IS NOT NULL) are excluded.
     */
    @Query("SELECT * FROM libraries WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeAll(): Flow<List<LibraryEntity>>

    /**
     * Observe a single library by ID reactively. Emits null when absent or tombstoned.
     */
    @Query("SELECT * FROM libraries WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<LibraryEntity?>

    /**
     * Snapshot lookup by ID. Returns null when absent or tombstoned.
     */
    @Query("SELECT * FROM libraries WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): LibraryEntity?

    /**
     * Snapshot of all libraries including tombstoned rows.
     *
     * Used by diagnostic and sync paths that need to inspect the full table.
     */
    @Query("SELECT * FROM libraries")
    suspend fun findAll(): List<LibraryEntity>

    /**
     * Upsert (insert or replace) a library entity. Used by both SSE events and
     * catch-up pages to apply server state.
     */
    @Upsert
    suspend fun upsert(entity: LibraryEntity)

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     *
     * The `updatedAt` column is also advanced to [deletedAt] so live queries
     * that filter on `deletedAt IS NULL` correctly exclude this row.
     */
    @Query("UPDATE libraries SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** All rows (including tombstones) with [revision][LibraryEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM libraries WHERE revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM libraries WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    /**
     * Epoch-ms when the live library's first-ever scan completed, or null when it hasn't yet (or no
     * live library exists). The server-authoritative signal the initial-population gate reads at
     * scan-arm time — non-null means the shell must never show "Building your library" again.
     */
    @Query("SELECT initialScanCompletedAt FROM libraries WHERE deletedAt IS NULL LIMIT 1")
    suspend fun initialScanCompletedAt(): Long?

    /**
     * Reactively true while any live library still has no recorded initial-scan completion. Combined
     * with book-emptiness to derive the initial-population ("Building your library") gate: a populated
     * library, or one the server has stamped complete, reads false so the shell mounts immediately.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM libraries WHERE deletedAt IS NULL AND initialScanCompletedAt IS NULL)")
    fun observeHasIncompleteInitialScan(): Flow<Boolean>
}
