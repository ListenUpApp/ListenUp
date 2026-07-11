package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ReadingOrderEntity] sync-substrate operations (Reading Orders — Room v3).
 *
 * The local mirror holds only the authenticated user's own reading orders.
 * Tombstones are soft-deletes: [ReadingOrderEntity.deletedAt] is set to a non-null
 * epoch-ms value when an order is removed. All observation queries exclude
 * tombstones. `bookCount` is JOIN-derived (no denormalized column). Mirrors
 * [ShelfDao].
 */
@Dao
internal interface ReadingOrderDao {
    /** Insert or update a reading order. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(readingOrder: ReadingOrderEntity)

    /** Apply a server tombstone: set [ReadingOrderEntity.deletedAt] and advance [ReadingOrderEntity.revision]. */
    @Query(
        "UPDATE reading_orders SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned reading order by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM reading_orders WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): ReadingOrderEntity?

    /** Observe a single reading order by primary key, emitting null when absent or tombstoned. */
    @Query("SELECT * FROM reading_orders WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<ReadingOrderEntity?>

    /**
     * Observe the caller's non-tombstoned reading orders with their live book counts,
     * ordered by most-recently-updated first.
     *
     * `bookCount` counts live (non-tombstoned) [ReadingOrderBookEntity] rows per order
     * via LEFT JOIN — the [ShelfDao.observeMyShelvesWithBookCount] precedent.
     */
    @Query(
        """
        SELECT r.*, COALESCE(b.cnt, 0) AS bookCount
        FROM reading_orders r
        LEFT JOIN (
            SELECT readingOrderId, COUNT(*) AS cnt
            FROM reading_order_books
            WHERE deletedAt IS NULL
            GROUP BY readingOrderId
        ) b ON b.readingOrderId = r.id
        WHERE r.deletedAt IS NULL
        ORDER BY r.updatedAt DESC
    """,
    )
    fun observeMyReadingOrdersWithBookCount(): Flow<List<ReadingOrderWithBookCount>>

    /**
     * Cover hashes for the first few live books on a reading order, in sort order.
     *
     * Used to render the order-card cover grid offline. Joins the live junction rows
     * to the local `books` mirror; only books present in Room with a non-null cover
     * are returned.
     */
    @Query(
        """
        SELECT b.coverHash
        FROM reading_order_books rb
        JOIN books b ON rb.bookId = b.id
        WHERE rb.readingOrderId = :readingOrderId AND rb.deletedAt IS NULL AND b.coverHash IS NOT NULL
        ORDER BY rb.sortOrder ASC
        LIMIT 4
    """,
    )
    suspend fun coverHashesFor(readingOrderId: String): List<String>

    /**
     * True live (non-tombstoned) book count for a single reading order — the
     * single-order mapping paths use this so `bookCount` is always the full
     * junction count, not the cover-grid LIMIT.
     */
    @Query("SELECT COUNT(*) FROM reading_order_books WHERE readingOrderId = :readingOrderId AND deletedAt IS NULL")
    suspend fun bookCountFor(readingOrderId: String): Int

    /**
     * Sum of audio duration (ms) across the reading order's live books present in
     * the local mirror. Books not yet synced to Room contribute zero.
     */
    @Query(
        """
        SELECT COALESCE(SUM(b.totalDuration), 0)
        FROM reading_order_books rb
        JOIN books b ON rb.bookId = b.id
        WHERE rb.readingOrderId = :readingOrderId AND rb.deletedAt IS NULL
    """,
    )
    suspend fun totalDurationMsFor(readingOrderId: String): Long

    /** Delete all reading-order rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM reading_orders")
    suspend fun deleteAll()

    /** Live rows with [revision][ReadingOrderEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM reading_orders WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM reading_orders WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
