package com.calypsan.listenup.client.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [LibraryFolderEntity] operations.
 *
 * Live queries filter out tombstoned rows (`deletedAt IS NULL`). The index on
 * `libraryId` makes library-scoped queries efficient.
 */
@Dao
internal interface LibraryFolderDao {
    /**
     * Observe all live folders reactively across all libraries.
     *
     * Tombstoned rows are excluded.
     */
    @Query("SELECT * FROM library_folders WHERE deletedAt IS NULL ORDER BY libraryId ASC")
    fun observeAll(): Flow<List<LibraryFolderEntity>>

    /**
     * Observe all live folders for a specific library reactively.
     *
     * Tombstoned rows are excluded.
     *
     * @param libraryId The parent library ID
     */
    @Query(
        "SELECT * FROM library_folders WHERE libraryId = :libraryId AND deletedAt IS NULL " +
            "ORDER BY rootPath ASC",
    )
    fun observeForLibrary(libraryId: String): Flow<List<LibraryFolderEntity>>

    /**
     * Snapshot lookup by folder ID. Returns null when absent.
     */
    @Query("SELECT * FROM library_folders WHERE id = :id")
    suspend fun findById(id: String): LibraryFolderEntity?

    /**
     * Snapshot of all live folders for a specific library.
     *
     * Used by [com.calypsan.listenup.client.data.repository.LibraryRepositoryImpl]
     * to resolve a library's full folder list for domain model construction.
     *
     * @param libraryId The parent library ID
     */
    @Query(
        "SELECT * FROM library_folders WHERE libraryId = :libraryId AND deletedAt IS NULL",
    )
    suspend fun findAllForLibrary(libraryId: String): List<LibraryFolderEntity>

    /**
     * Upsert (insert or replace) a library folder entity.
     */
    @Upsert
    suspend fun upsert(entity: LibraryFolderEntity)

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     */
    @Query("UPDATE library_folders SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** All rows (including tombstones) with [revision][LibraryFolderEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM library_folders WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM library_folders WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
