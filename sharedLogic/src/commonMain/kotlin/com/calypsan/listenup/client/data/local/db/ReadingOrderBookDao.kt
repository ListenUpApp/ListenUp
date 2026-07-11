package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ReadingOrderBookEntity] junction sync operations (Reading Orders — Room v3).
 *
 * Soft-deletes are tombstoned via [ReadingOrderBookEntity.deletedAt]; observation
 * queries exclude tombstoned rows so the UI reactively reflects removals. The
 * synthetic id `"$readingOrderId:$bookId"` is the wire/sync-cursor identity.
 * Mirrors [ShelfBookDao], plus the optimistic-write helpers ([maxSortOrder],
 * [updateSortOrder]) the offline-first repository uses.
 */
@Dao
internal interface ReadingOrderBookDao {
    /** Insert or update a junction row. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(entity: ReadingOrderBookEntity)

    /** Tombstone a junction row by its synthetic id: set [ReadingOrderBookEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE reading_order_books SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Return the junction row for the given synthetic [id], or null if absent. */
    @Query("SELECT * FROM reading_order_books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReadingOrderBookEntity?

    /** Observe the live (non-tombstoned) book ids for a reading order, in sort order. */
    @Query(
        "SELECT bookId FROM reading_order_books " +
            "WHERE readingOrderId = :readingOrderId AND deletedAt IS NULL ORDER BY sortOrder ASC",
    )
    fun observeReadingOrderBooks(readingOrderId: String): Flow<List<String>>

    /** Live (non-tombstoned) junction rows for a reading order, in sort order. */
    @Query(
        "SELECT * FROM reading_order_books " +
            "WHERE readingOrderId = :readingOrderId AND deletedAt IS NULL ORDER BY sortOrder ASC",
    )
    suspend fun liveRowsFor(readingOrderId: String): List<ReadingOrderBookEntity>

    /** The highest live sort order in a reading order, or null when the order is empty. */
    @Query(
        "SELECT MAX(sortOrder) FROM reading_order_books WHERE readingOrderId = :readingOrderId AND deletedAt IS NULL",
    )
    suspend fun maxSortOrder(readingOrderId: String): Int?

    /** Rewrite one live junction row's sort order (the optimistic reorder write). */
    @Query("UPDATE reading_order_books SET sortOrder = :sortOrder WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateSortOrder(
        id: String,
        sortOrder: Int,
    )

    /** Delete all junction rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM reading_order_books")
    suspend fun deleteAll()

    /**
     * Live rows with [revision][ReadingOrderBookEntity.revision] <= [max], for digest computation.
     *
     * The id is the synthetic `"$readingOrderId:$bookId"` — the same form the server uses on the wire.
     */
    @Query("SELECT id AS id, revision FROM reading_order_books WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with synthetic [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM reading_order_books WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
