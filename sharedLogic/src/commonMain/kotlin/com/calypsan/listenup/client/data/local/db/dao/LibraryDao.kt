package com.calypsan.listenup.client.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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
interface LibraryDao {
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
}
