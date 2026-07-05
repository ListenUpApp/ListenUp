package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ShelfBookEntity] junction sync operations (Shelves — Room v26).
 *
 * Soft-deletes are tombstoned via [ShelfBookEntity.deletedAt]; observation queries
 * exclude tombstoned rows so the UI reactively reflects removals. The synthetic id
 * `"$shelfId:$bookId"` is the wire/sync-cursor identity. Mirrors [CollectionBookDao].
 */
@Dao
internal interface ShelfBookDao {
    /** Insert or update a junction row. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(entity: ShelfBookEntity)

    /** Insert or update multiple junction rows in one operation. */
    @Upsert
    suspend fun upsertAll(entities: List<ShelfBookEntity>)

    /** Tombstone a junction row by its synthetic id: set [ShelfBookEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE shelf_books SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Return the junction row for the given synthetic [id], or null if absent. */
    @Query("SELECT * FROM shelf_books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ShelfBookEntity?

    /** Observe the live (non-tombstoned) book ids for a shelf, in sort order. */
    @Query(
        "SELECT bookId FROM shelf_books WHERE shelfId = :shelfId AND deletedAt IS NULL ORDER BY sortOrder ASC",
    )
    fun observeShelfBooks(shelfId: String): Flow<List<String>>

    /** Delete all junction rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM shelf_books")
    suspend fun deleteAll()

    /**
     * All rows (including tombstones) with [revision][ShelfBookEntity.revision] <= [max], for digest computation.
     *
     * The id is the synthetic `"$shelfId:$bookId"` — the same form the server uses on the wire.
     */
    @Query("SELECT id AS id, revision FROM shelf_books WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with synthetic [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM shelf_books WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
