package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

/** Room DAO for the [ReadingOrderEntity] mirror (#962). */
@Dao
internal interface ReadingOrderDao {
    /** Insert or replace an order. */
    @Upsert
    suspend fun upsert(order: ReadingOrderEntity)

    /** Apply a tombstone: set [ReadingOrderEntity.deletedAt] and advance its revision. */
    @Query(
        "UPDATE reading_orders SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** The order with [id] in any state, or null. */
    @Query("SELECT * FROM reading_orders WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReadingOrderEntity?

    /** Live rows with revision <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM reading_orders WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM reading_orders WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    /** Delete every order (library reset). */
    @Query("DELETE FROM reading_orders")
    suspend fun deleteAll()
}

/** Room DAO for the [ReadingOrderBookEntity] mirror (#962). */
@Dao
internal interface ReadingOrderBookDao {
    /** Insert or replace a membership, keyed by its natural pair. */
    @Upsert
    suspend fun upsert(member: ReadingOrderBookEntity)

    /** Tombstone by wire id — tombstones arrive with the pair blanked. Returns rows changed. */
    @Query("UPDATE reading_order_books SET deletedAt = :deletedAt, revision = :revision WHERE syncId = :syncId")
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
        revision: Long,
    ): Int

    /** Live members of [readingOrderId], first to last. */
    @Query(
        "SELECT * FROM reading_order_books WHERE readingOrderId = :readingOrderId AND deletedAt IS NULL " +
            "ORDER BY position ASC, syncId ASC",
    )
    suspend fun liveForOrder(readingOrderId: String): List<ReadingOrderBookEntity>

    /** The row for the pair in any state, or null. */
    @Query("SELECT * FROM reading_order_books WHERE readingOrderId = :readingOrderId AND bookId = :bookId LIMIT 1")
    suspend fun find(
        readingOrderId: String,
        bookId: String,
    ): ReadingOrderBookEntity?

    /** Wire ids of every live membership — the access gate's sweep set. */
    @Query("SELECT syncId FROM reading_order_books WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /** Wire ids of live memberships of [bookIds] — the access gate's targeted candidates. */
    @Query("SELECT syncId FROM reading_order_books WHERE bookId IN (:bookIds) AND deletedAt IS NULL")
    suspend fun liveSyncIdsForBooks(bookIds: List<String>): List<String>

    /** Tombstones the live memberships whose wire id is in [ids] — the access gate's prune. */
    @Query("UPDATE reading_order_books SET deletedAt = :now WHERE deletedAt IS NULL AND syncId IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Live rows with revision <= [max], by wire id, for digest computation. */
    @Query("SELECT syncId AS id, revision FROM reading_order_books WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with wire id [syncId]; null when never seen. */
    @Query("SELECT revision FROM reading_order_books WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?

    /** Delete every membership (library reset). */
    @Query("DELETE FROM reading_order_books")
    suspend fun deleteAll()
}

/** Room DAO for the [ReadingOrderFollowEntity] mirror (#962). */
@Dao
internal interface ReadingOrderFollowDao {
    /** Insert or replace a choice. */
    @Upsert
    suspend fun upsert(follow: ReadingOrderFollowEntity)

    /** Apply a tombstone — the series inherits again. */
    @Query(
        "UPDATE reading_order_follows SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt " +
            "WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** The choice with [id] in any state, or null. */
    @Query("SELECT * FROM reading_order_follows WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReadingOrderFollowEntity?

    /** Live rows with revision <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM reading_order_follows WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM reading_order_follows WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    /** Delete every choice (library reset). */
    @Query("DELETE FROM reading_order_follows")
    suspend fun deleteAll()
}
