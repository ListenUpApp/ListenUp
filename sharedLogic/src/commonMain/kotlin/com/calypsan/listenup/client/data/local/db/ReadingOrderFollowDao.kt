package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ReadingOrderFollowEntity] follow-state operations (Reading Orders — Room v3).
 *
 * One live row per series (the mirror holds only the caller's own rows). Reads key
 * by [ReadingOrderFollowEntity.seriesId]; sync writes key by the deterministic
 * synthetic id `"$userId:$seriesId"` shared with the server.
 */
@Dao
internal interface ReadingOrderFollowDao {
    /** Insert or update a follow row. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(entity: ReadingOrderFollowEntity)

    /** Apply a server tombstone: set [ReadingOrderFollowEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE reading_order_follows SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt " +
            "WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Return the follow row for the given synthetic [id], or null if absent. */
    @Query("SELECT * FROM reading_order_follows WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReadingOrderFollowEntity?

    /**
     * Observe the active reading-order id for [seriesId] — null when no live follow
     * row exists or the follow is explicitly reset (the per-book frontier floor).
     */
    @Query(
        "SELECT activeReadingOrderId FROM reading_order_follows " +
            "WHERE seriesId = :seriesId AND deletedAt IS NULL LIMIT 1",
    )
    fun observeActiveReadingOrderId(seriesId: String): Flow<String?>

    /** The live follow row for [seriesId], or null. */
    @Query("SELECT * FROM reading_order_follows WHERE seriesId = :seriesId AND deletedAt IS NULL LIMIT 1")
    suspend fun getBySeries(seriesId: String): ReadingOrderFollowEntity?

    /** Delete all follow rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM reading_order_follows")
    suspend fun deleteAll()

    /** Live rows with [revision][ReadingOrderFollowEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM reading_order_follows WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM reading_order_follows WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
