package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [EntityEntity] sync-substrate operations (Story World Stage 2).
 *
 * Tombstones are soft-deletes: [EntityEntity.deletedAt] is set to a non-null epoch-ms value
 * when an entity is removed. All observation queries exclude tombstones. Mirrors [SeriesDao].
 */
@Dao
internal interface EntityDao {
    /** Insert or update an entity. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(entity: EntityEntity)

    /** Retrieve a single non-tombstoned entity by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM entities WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): EntityEntity?

    /** Observe a single entity by primary key, emitting null when absent or tombstoned. */
    @Query("SELECT * FROM entities WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<EntityEntity?>

    /** Observe every non-tombstoned entity namespaced under [seriesId], ordered by name. */
    @Query("SELECT * FROM entities WHERE homeSeriesId = :seriesId AND deletedAt IS NULL ORDER BY name ASC")
    fun observeForSeries(seriesId: String): Flow<List<EntityEntity>>

    /** Observe every non-tombstoned entity namespaced under standalone [bookId], ordered by name. */
    @Query("SELECT * FROM entities WHERE homeBookId = :bookId AND deletedAt IS NULL ORDER BY name ASC")
    fun observeForBook(bookId: String): Flow<List<EntityEntity>>

    /** Apply a server tombstone: set [EntityEntity.deletedAt] and advance [EntityEntity.revision]. */
    @Query(
        "UPDATE entities SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Delete all entity rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM entities")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][EntityEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM entities WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM entities WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
