package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [CollectionEntity] sync-substrate operations (Collections — Room v24).
 *
 * Tombstones are soft-deletes: [CollectionEntity.deletedAt] is set to a non-null
 * epoch-ms value when a collection is removed. All observation queries exclude
 * tombstones. `bookCount` is JOIN-derived (no denormalized column) — see
 * [observeAllWithBookCount]. Mirrors [TagDao].
 */
@Dao
internal interface CollectionDao {
    /** Insert or update a collection. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    /** Insert or update multiple collections in one operation. */
    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    /** Apply a server tombstone: set [CollectionEntity.deletedAt] and advance [CollectionEntity.revision]. */
    @Query(
        "UPDATE collections SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned collection by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM collections WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): CollectionEntity?

    /** Observe a single collection by primary key, emitting null when absent or tombstoned. */
    @Query("SELECT * FROM collections WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<CollectionEntity?>

    /**
     * Observe all non-tombstoned collections with their live book counts, ordered by name.
     *
     * `bookCount` counts live (non-tombstoned) [CollectionBookEntity] rows per collection
     * via LEFT JOIN — the [GenreDao.observeAllGenresWithBookCount] precedent.
     */
    @Query(
        """
        SELECT c.*, COALESCE(b.cnt, 0) AS bookCount
        FROM collections c
        LEFT JOIN (
            SELECT collectionId, COUNT(*) AS cnt
            FROM collection_books
            WHERE deletedAt IS NULL
            GROUP BY collectionId
        ) b ON b.collectionId = c.id
        WHERE c.deletedAt IS NULL
        ORDER BY c.name ASC
    """,
    )
    fun observeAllWithBookCount(): Flow<List<CollectionWithBookCount>>

    /** Live (non-tombstoned) collection ids — used by the access-change reconcile. */
    @Query("SELECT id FROM collections WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone the given live collections by id — the chunked access-change prune.
     *
     * Local-only eviction: a collection the caller can no longer see is soft-deleted so the UI
     * drops it; accessible rows are untouched. The existing `revision` is preserved (this is not a
     * server tombstone). The composed handler computes the doomed set in Kotlin and calls this with
     * id chunks bounded under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collections SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND id IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all collection rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collections")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][CollectionEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM collections WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM collections WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}

/**
 * Room DAO for [CollectionBookEntity] junction sync operations (Collections — Room v24).
 *
 * Soft-deletes are tombstoned via [CollectionBookEntity.deletedAt]; observation queries
 * exclude tombstoned rows so the UI reactively reflects removals. Mirrors [BookTagDao].
 */
@Dao
internal interface CollectionBookDao {
    /** Insert or update a junction row. Replaces on conflict using the composite primary key. */
    @Upsert
    suspend fun upsert(entity: CollectionBookEntity)

    /** Tombstone a junction row: set [CollectionBookEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE collection_books SET deletedAt = :deletedAt, revision = :revision " +
            "WHERE collectionId = :collectionId AND bookId = :bookId",
    )
    suspend fun tombstone(
        collectionId: String,
        bookId: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Return the junction row for the given [collectionId]/[bookId] pair, or null if absent. */
    @Query("SELECT * FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId LIMIT 1")
    suspend fun findByKey(
        collectionId: String,
        bookId: String,
    ): CollectionBookEntity?

    /** Observe the live (non-tombstoned) book ids for a collection. */
    @Query(
        "SELECT bookId FROM collection_books WHERE collectionId = :collectionId AND deletedAt IS NULL ORDER BY createdAt ASC",
    )
    fun observeBookIds(collectionId: String): Flow<List<String>>

    /** Observe the live (non-tombstoned) collection ids a book currently belongs to. */
    @Query(
        "SELECT collectionId FROM collection_books WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt ASC",
    )
    fun observeCollectionIdsForBook(bookId: String): Flow<List<String>>

    /**
     * Live (non-tombstoned) junction ids in the synthetic `"$collectionId:$bookId"` form the
     * server uses on the wire — used by the access-change reconcile so the local set lines up
     * with `catchUpTransient`'s returned set.
     */
    @Query("SELECT collectionId || ':' || bookId FROM collection_books WHERE deletedAt IS NULL")
    suspend fun liveSyntheticIds(): List<String>

    /**
     * Tombstone the given live junction rows by synthetic `"$collectionId:$bookId"` id — the
     * chunked access-change prune.
     *
     * Local-only eviction. The existing `revision` is preserved (this is not a server tombstone).
     * The composed handler computes the doomed set in Kotlin and calls this with id chunks bounded
     * under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collection_books SET deletedAt = :now " +
            "WHERE deletedAt IS NULL AND (collectionId || ':' || bookId) IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all junction rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collection_books")
    suspend fun deleteAll()

    /**
     * All rows (including tombstones) with [revision][CollectionBookEntity.revision] <= [max], for digest computation.
     *
     * The synthetic id is `"$collectionId:$bookId"` — the same form the server uses on the wire.
     */
    @Query(
        "SELECT collectionId || ':' || bookId AS id, revision FROM collection_books WHERE deletedAt IS NULL AND revision <= :max",
    )
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the junction row for [collectionId]/[bookId], tombstones included;
     * null when the row has never been seen.
     */
    @Query("SELECT revision FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId LIMIT 1")
    suspend fun revisionOf(
        collectionId: String,
        bookId: String,
    ): Long?
}

/**
 * Room DAO for [CollectionShareEntity] sync operations (Collections — Room v24).
 *
 * Soft-deletes via [CollectionShareEntity.deletedAt] represent revoked shares;
 * observation queries exclude tombstoned rows. Mirrors [TagDao].
 */
@Dao
internal interface CollectionShareDao {
    /** Insert or update a share. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(share: CollectionShareEntity)

    /** Apply a server tombstone: set [CollectionShareEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE collection_shares SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned share by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM collection_shares WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): CollectionShareEntity?

    /** Observe live (non-tombstoned) shares for a collection, ordered by recipient. */
    @Query(
        "SELECT * FROM collection_shares WHERE collectionId = :collectionId AND deletedAt IS NULL ORDER BY sharedWithUserId ASC",
    )
    fun observeForCollection(collectionId: String): Flow<List<CollectionShareEntity>>

    /** Live (non-tombstoned) share ids — used by the access-change reconcile. */
    @Query("SELECT id FROM collection_shares WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone the given live shares by id — the chunked access-change prune.
     *
     * Local-only eviction. The existing `revision` is preserved (this is not a server tombstone).
     * The composed handler computes the doomed set in Kotlin and calls this with id chunks bounded
     * under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collection_shares SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND id IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all share rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collection_shares")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][CollectionShareEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM collection_shares WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM collection_shares WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
