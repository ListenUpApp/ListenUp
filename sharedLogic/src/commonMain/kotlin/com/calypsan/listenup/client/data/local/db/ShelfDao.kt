package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ShelfEntity] sync-substrate operations (Shelves — Room v26).
 *
 * The local mirror holds only the authenticated user's own shelves. Tombstones are
 * soft-deletes: [ShelfEntity.deletedAt] is set to a non-null epoch-ms value when a
 * shelf is removed. All observation queries exclude tombstones. `bookCount` is
 * JOIN-derived (no denormalized column) — see [observeMyShelvesWithBookCount].
 * Mirrors [CollectionDao].
 */
@Dao
internal interface ShelfDao {
    /** Insert or update a shelf. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(shelf: ShelfEntity)

    /** Insert or update multiple shelves in one operation. */
    @Upsert
    suspend fun upsertAll(shelves: List<ShelfEntity>)

    /** Apply a server tombstone: set [ShelfEntity.deletedAt] and advance [ShelfEntity.revision]. */
    @Query(
        "UPDATE shelves SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned shelf by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM shelves WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): ShelfEntity?

    /** Observe a single shelf by primary key, emitting null when absent or tombstoned. */
    @Query("SELECT * FROM shelves WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<ShelfEntity?>

    /**
     * Observe the caller's non-tombstoned shelves with their live book counts, ordered by
     * most-recently-updated first.
     *
     * `bookCount` counts live (non-tombstoned) [ShelfBookEntity] rows per shelf via LEFT JOIN —
     * the [CollectionDao.observeAllWithBookCount] precedent.
     */
    @Query(
        """
        SELECT s.*, COALESCE(b.cnt, 0) AS bookCount
        FROM shelves s
        LEFT JOIN (
            SELECT shelfId, COUNT(*) AS cnt
            FROM shelf_books
            WHERE deletedAt IS NULL
            GROUP BY shelfId
        ) b ON b.shelfId = s.id
        WHERE s.deletedAt IS NULL
        ORDER BY s.updatedAt DESC
    """,
    )
    fun observeMyShelvesWithBookCount(): Flow<List<ShelfWithBookCount>>

    /**
     * Observe the caller's non-tombstoned shelves that currently contain [bookId], with live
     * book counts, ordered alphabetically (case-insensitive).
     *
     * A shelf qualifies when it has a non-tombstoned [ShelfBookEntity] row for [bookId]. The
     * `bookCount` LEFT JOIN matches [observeMyShelvesWithBookCount] so the mapped
     * [com.calypsan.listenup.client.domain.model.Shelf.bookCount] is the shelf's full live
     * count, not 1. Reactive: re-emits whenever the relevant shelves or memberships change.
     * The local mirror holds only the caller's own shelves, so no owner predicate is needed.
     */
    @Query(
        """
        SELECT s.*, COALESCE(b.cnt, 0) AS bookCount
        FROM shelves s
        JOIN shelf_books m ON m.shelfId = s.id AND m.bookId = :bookId AND m.deletedAt IS NULL
        LEFT JOIN (
            SELECT shelfId, COUNT(*) AS cnt
            FROM shelf_books
            WHERE deletedAt IS NULL
            GROUP BY shelfId
        ) b ON b.shelfId = s.id
        WHERE s.deletedAt IS NULL
        ORDER BY s.name COLLATE NOCASE ASC
    """,
    )
    fun observeShelvesContainingBookWithBookCount(bookId: String): Flow<List<ShelfWithBookCount>>

    /**
     * Cover hashes for the first few live books on a shelf, in sort order.
     *
     * Used to render the shelf-card cover grid offline. Joins the live junction rows to
     * the local `books` mirror; only books present in Room with a non-null cover are returned.
     */
    @Query(
        """
        SELECT b.coverHash
        FROM shelf_books sb
        JOIN books b ON sb.bookId = b.id
        WHERE sb.shelfId = :shelfId AND sb.deletedAt IS NULL AND b.coverHash IS NOT NULL
        ORDER BY sb.sortOrder ASC
        LIMIT 4
    """,
    )
    suspend fun coverHashesFor(shelfId: String): List<String>

    /**
     * Per-book cover hashes for a shelf's live books present in the local mirror.
     *
     * The shelf-detail RPC view carries no cover info, so the detail mapper joins each
     * [ShelfBook][com.calypsan.listenup.client.domain.model.ShelfBook] to its local cover hash
     * here. Books not yet synced to Room are simply absent. Unlike [coverHashesFor] (a LIMIT-4
     * cover-grid helper) this returns one row per live book and includes null hashes.
     */
    @Query(
        """
        SELECT sb.bookId AS bookId, b.coverHash AS coverHash
        FROM shelf_books sb
        JOIN books b ON sb.bookId = b.id
        WHERE sb.shelfId = :shelfId AND sb.deletedAt IS NULL
        ORDER BY sb.sortOrder ASC
    """,
    )
    suspend fun coverHashesByBookFor(shelfId: String): List<ShelfBookCoverHash>

    /**
     * True live (non-tombstoned) book count for a single shelf.
     *
     * Used by the single-shelf mapping paths ([ShelfRepositoryImpl.observeById] /
     * [ShelfRepositoryImpl.getById]) so [com.calypsan.listenup.client.domain.model.Shelf.bookCount]
     * is always the full junction count, not the cover-grid LIMIT.
     */
    @Query("SELECT COUNT(*) FROM shelf_books WHERE shelfId = :shelfId AND deletedAt IS NULL")
    suspend fun bookCountFor(shelfId: String): Int

    /**
     * Sum of audio duration (ms) across the shelf's live books present in the local mirror.
     *
     * Books not yet synced to Room contribute zero. Returns 0 for an empty shelf.
     */
    @Query(
        """
        SELECT COALESCE(SUM(b.totalDuration), 0)
        FROM shelf_books sb
        JOIN books b ON sb.bookId = b.id
        WHERE sb.shelfId = :shelfId AND sb.deletedAt IS NULL
    """,
    )
    suspend fun totalDurationMsFor(shelfId: String): Long

    /** Live (non-tombstoned) shelf ids. */
    @Query("SELECT id FROM shelves WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /** Delete all shelf rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM shelves")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][ShelfEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM shelves WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM shelves WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
