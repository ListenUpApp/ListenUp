package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [TagEntity] sync-substrate operations (Tags — Room v22).
 *
 * Tombstones are soft-deletes: [TagEntity.deletedAt] is set to a non-null epoch-ms
 * value when a tag is removed. All observation queries exclude tombstones. The
 * [softDelete] method applies a server tombstone without removing the row, so the
 * sync engine can track deletions across devices.
 */
@Dao
internal interface TagDao {
    /**
     * Insert or update a tag entity. Replaces on conflict using the primary key.
     */
    @Upsert
    suspend fun upsert(tag: TagEntity)

    /**
     * Insert or update multiple tag entities in one operation.
     */
    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    /**
     * Apply a server tombstone: set [TagEntity.deletedAt] and advance [TagEntity.revision].
     */
    @Query("UPDATE tags SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Retrieve a single non-tombstoned tag by its primary key, or null if absent or deleted.
     */
    @Query("SELECT * FROM tags WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): TagEntity?

    /**
     * Observe a single tag by its primary key, emitting [TagEntity] or null when the tag
     * is absent or tombstoned. Re-emits on any change to the row.
     */
    @Query("SELECT * FROM tags WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<TagEntity?>

    /**
     * Retrieve a single non-tombstoned tag by its URL-safe slug, or null if absent or deleted.
     */
    @Query("SELECT * FROM tags WHERE slug = :slug AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySlug(slug: String): TagEntity?

    /**
     * Retrieve a single non-tombstoned tag whose display name matches [name] case-insensitively, or
     * null if none. Powers the offline-first add-to-book path: a same-name tag's slug equals the
     * server's `normalize(name)`, so its id is exactly what find-or-create would resolve to. Slug is
     * unique, so at most one live tag can match; the `id ASC LIMIT 1` tie-break is a deterministic
     * belt-and-braces guard against a transient duplicate mid-sync.
     */
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE AND deletedAt IS NULL ORDER BY id ASC LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    /**
     * Observe all non-tombstoned tags ordered by name ascending.
     */
    @Query("SELECT * FROM tags WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    /**
     * Observe all non-tombstoned tags currently applied to [bookId], ordered by name ascending.
     *
     * Excludes tags whose [TagEntity.deletedAt] is non-null AND junction rows whose
     * [BookTagEntity.deletedAt] is non-null, so a removed tag disappears reactively.
     */
    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN book_tags bt ON bt.tagId = t.id
        WHERE bt.bookId = :bookId
          AND bt.deletedAt IS NULL
          AND t.deletedAt IS NULL
        ORDER BY t.name ASC
        """,
    )
    fun observeForBook(bookId: String): Flow<List<TagEntity>>

    /**
     * Delete all tag rows (used in tests and full re-sync scenarios).
     */
    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][TagEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM tags WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM tags WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}

/**
 * Room DAO for [BookTagEntity] junction sync operations (Tags — Room v22).
 *
 * The junction is global (cross-user curator model): one book has one shared tag set.
 * Soft-deletes are tombstoned via [BookTagEntity.deletedAt]; observation queries exclude
 * tombstoned rows so the UI reactively reflects removals.
 */
@Dao
internal interface BookTagDao {
    /**
     * Insert or update a junction row. Replaces on conflict using the composite primary key.
     */
    @Upsert
    suspend fun upsert(entity: BookTagEntity)

    /**
     * Tombstone a junction row: set [BookTagEntity.deletedAt] and its [BookTagEntity.revision] to the
     * server-authoritative [revision]. Writing the event's own revision (rather than `revision + 1`)
     * makes a replayed `Deleted` frame a true no-op, converging with the `collection_books` junction
     * (previously this double-incremented on replay).
     */
    @Query(
        "UPDATE book_tags SET deletedAt = :deletedAt, revision = :revision WHERE bookId = :bookId AND tagId = :tagId",
    )
    suspend fun tombstone(
        bookId: String,
        tagId: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Tombstone a junction row by its opaque wire [syncId] (SERVER-SYNC-04) — the by-identity
     * counterpart to [tombstone], used when applying a `SyncEvent.Deleted` frame whose payload
     * has its natural pair blanked (junction tombstones ship identity only). Returns the number
     * of rows affected (0 when [syncId] matches no local row — a graceful no-op the caller logs,
     * since there is no longer a composite id to parse and fail on).
     */
    @Query("UPDATE book_tags SET deletedAt = :deletedAt, revision = :revision WHERE syncId = :syncId")
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
        revision: Long,
    ): Int

    /**
     * Cascade-tombstone every live junction row for [tagId] — the client mirror of the server's
     * `deleteTag` cascade (soft-delete all `book_tags` for the tag). Advances each row's revision so
     * the server's own cascade echo (at least one higher) still applies through the revision guard.
     */
    @Query(
        "UPDATE book_tags SET deletedAt = :deletedAt, revision = revision + 1 WHERE tagId = :tagId AND deletedAt IS NULL",
    )
    suspend fun tombstoneAllForTag(
        tagId: String,
        deletedAt: Long,
    )

    /**
     * Return the junction row for the given [bookId]/[tagId] pair, or null if absent.
     */
    @Query("SELECT * FROM book_tags WHERE bookId = :bookId AND tagId = :tagId LIMIT 1")
    suspend fun findByKey(
        bookId: String,
        tagId: String,
    ): BookTagEntity?

    /**
     * Observe all live (non-tombstoned) junction rows for a given [bookId].
     */
    @Query("SELECT * FROM book_tags WHERE bookId = :bookId AND deletedAt IS NULL")
    fun observeForBook(bookId: String): Flow<List<BookTagEntity>>

    /**
     * Observe all live (non-tombstoned) junction rows for a given [tagId].
     */
    @Query("SELECT * FROM book_tags WHERE tagId = :tagId AND deletedAt IS NULL")
    fun observeForTag(tagId: String): Flow<List<BookTagEntity>>

    /**
     * Delete all junction rows (used in tests and full re-sync scenarios).
     */
    @Query("DELETE FROM book_tags")
    suspend fun deleteAll()

    /**
     * All rows (including tombstones) with [revision][BookTagEntity.revision] <= [max], for digest computation.
     *
     * The id is the opaque wire [BookTagEntity.syncId] (SERVER-SYNC-04) — the same value the
     * server uses on the wire.
     */
    @Query("SELECT syncId AS id, revision FROM book_tags WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the junction row for [bookId]/[tagId], tombstones included; null
     * when the row has never been seen.
     */
    @Query("SELECT revision FROM book_tags WHERE bookId = :bookId AND tagId = :tagId LIMIT 1")
    suspend fun revisionOf(
        bookId: String,
        tagId: String,
    ): Long?

    /**
     * The stored revision of the junction row with opaque wire [syncId] (SERVER-SYNC-04),
     * tombstones included; null when the row has never been seen. The by-identity counterpart to
     * [revisionOf], used by the [ConflictPolicy.ServerWins] guard now that the wire id no longer
     * decomposes into the natural pair.
     */
    @Query("SELECT revision FROM book_tags WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?
}
