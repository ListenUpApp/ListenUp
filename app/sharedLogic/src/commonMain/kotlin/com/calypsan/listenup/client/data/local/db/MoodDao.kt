package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [MoodEntity] sync-substrate operations.
 *
 * Tombstones are soft-deletes: [MoodEntity.deletedAt] is set to a non-null epoch-ms
 * value when a mood is removed. All observation queries exclude tombstones. The
 * [softDelete] method applies a server tombstone without removing the row, so the
 * sync engine can track deletions across devices.
 *
 * Mirrors [TagDao] — moods share the tag stack's shape and sync discipline.
 */
@Dao
internal interface MoodDao {
    /**
     * Insert or update a mood entity. Replaces on conflict using the primary key.
     */
    @Upsert
    suspend fun upsert(mood: MoodEntity)

    /**
     * Insert or update multiple mood entities in one operation.
     */
    @Upsert
    suspend fun upsertAll(moods: List<MoodEntity>)

    /**
     * Apply a server tombstone: set [MoodEntity.deletedAt] and advance [MoodEntity.revision].
     */
    @Query("UPDATE moods SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Retrieve a single non-tombstoned mood by its primary key, or null if absent or deleted.
     */
    @Query("SELECT * FROM moods WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): MoodEntity?

    /**
     * Observe a single mood by its primary key, emitting [MoodEntity] or null when the mood
     * is absent or tombstoned. Re-emits on any change to the row.
     */
    @Query("SELECT * FROM moods WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<MoodEntity?>

    /**
     * Retrieve a single non-tombstoned mood by its URL-safe slug, or null if absent or deleted.
     */
    @Query("SELECT * FROM moods WHERE slug = :slug AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySlug(slug: String): MoodEntity?

    /**
     * Retrieve a single non-tombstoned mood whose display name matches [name] case-insensitively, or
     * null if none. Powers the offline-first add-to-book path: a same-name mood's slug equals the
     * server's `normalize(name)`, so its id is exactly what find-or-create would resolve to. Slug is
     * unique, so at most one live mood can match; the `id ASC LIMIT 1` tie-break is a deterministic
     * belt-and-braces guard against a transient duplicate mid-sync.
     */
    @Query("SELECT * FROM moods WHERE name = :name COLLATE NOCASE AND deletedAt IS NULL ORDER BY id ASC LIMIT 1")
    suspend fun findByName(name: String): MoodEntity?

    /**
     * Observe all non-tombstoned moods ordered by name ascending.
     */
    @Query("SELECT * FROM moods WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeAll(): Flow<List<MoodEntity>>

    /**
     * Observe all non-tombstoned moods currently applied to [bookId], ordered by name ascending.
     *
     * Excludes moods whose [MoodEntity.deletedAt] is non-null AND junction rows whose
     * [BookMoodEntity.deletedAt] is non-null, so a removed mood disappears reactively.
     */
    @Query(
        """
        SELECT m.* FROM moods m
        INNER JOIN book_moods bm ON bm.moodId = m.id
        WHERE bm.bookId = :bookId
          AND bm.deletedAt IS NULL
          AND m.deletedAt IS NULL
        ORDER BY m.name ASC
        """,
    )
    fun observeForBook(bookId: String): Flow<List<MoodEntity>>

    /**
     * Delete all mood rows (used in tests and full re-sync scenarios).
     */
    @Query("DELETE FROM moods")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][MoodEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM moods WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM moods WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}

/**
 * Room DAO for [BookMoodEntity] junction sync operations.
 *
 * The junction is global (cross-user curator model): one book has one shared mood set.
 * Soft-deletes are tombstoned via [BookMoodEntity.deletedAt]; observation queries exclude
 * tombstoned rows so the UI reactively reflects removals.
 *
 * Mirrors [BookTagDao].
 */
@Dao
internal interface BookMoodDao {
    /**
     * Insert or update a junction row. Replaces on conflict using the composite primary key.
     */
    @Upsert
    suspend fun upsert(entity: BookMoodEntity)

    /**
     * Tombstone a junction row: set [BookMoodEntity.deletedAt] and advance [BookMoodEntity.revision].
     */
    @Query(
        "UPDATE book_moods SET deletedAt = :deletedAt, revision = revision + 1 WHERE bookId = :bookId AND moodId = :moodId",
    )
    suspend fun tombstone(
        bookId: String,
        moodId: String,
        deletedAt: Long,
    )

    /**
     * Tombstone a junction row by its opaque wire [syncId] (SERVER-SYNC-04) — the by-identity
     * counterpart to [tombstone], used when applying a `SyncEvent.Deleted` frame whose payload
     * has its natural pair blanked (junction tombstones ship identity only). Preserves
     * [tombstone]'s existing `revision + 1` semantics (the event's own revision is not taken).
     * Returns the number of rows affected (0 when [syncId] matches no local row — a graceful
     * no-op the caller logs, since there is no longer a composite id to parse and fail on).
     */
    @Query("UPDATE book_moods SET deletedAt = :deletedAt, revision = revision + 1 WHERE syncId = :syncId")
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
    ): Int

    /**
     * Return the junction row for the given [bookId]/[moodId] pair, or null if absent.
     */
    @Query("SELECT * FROM book_moods WHERE bookId = :bookId AND moodId = :moodId LIMIT 1")
    suspend fun findByKey(
        bookId: String,
        moodId: String,
    ): BookMoodEntity?

    /**
     * Observe all live (non-tombstoned) junction rows for a given [bookId].
     */
    @Query("SELECT * FROM book_moods WHERE bookId = :bookId AND deletedAt IS NULL")
    fun observeForBook(bookId: String): Flow<List<BookMoodEntity>>

    /**
     * Observe all live (non-tombstoned) junction rows for a given [moodId].
     */
    @Query("SELECT * FROM book_moods WHERE moodId = :moodId AND deletedAt IS NULL")
    fun observeForMood(moodId: String): Flow<List<BookMoodEntity>>

    /**
     * Delete all junction rows (used in tests and full re-sync scenarios).
     */
    @Query("DELETE FROM book_moods")
    suspend fun deleteAll()

    /**
     * All rows (including tombstones) with [revision][BookMoodEntity.revision] <= [max], for digest computation.
     *
     * The id is the opaque wire [BookMoodEntity.syncId] (SERVER-SYNC-04) — the same value the
     * server uses on the wire.
     */
    @Query(
        "SELECT syncId AS id, revision FROM book_moods WHERE deletedAt IS NULL AND revision <= :max",
    )
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the junction row for [bookId]/[moodId], tombstones included; null
     * when the row has never been seen.
     */
    @Query("SELECT revision FROM book_moods WHERE bookId = :bookId AND moodId = :moodId LIMIT 1")
    suspend fun revisionOf(
        bookId: String,
        moodId: String,
    ): Long?

    /**
     * The stored revision of the junction row with opaque wire [syncId] (SERVER-SYNC-04),
     * tombstones included; null when the row has never been seen. The by-identity counterpart to
     * [revisionOf], used by the [ConflictPolicy.ServerWins] guard now that the wire id no longer
     * decomposes into the natural pair.
     */
    @Query("SELECT revision FROM book_moods WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?
}
