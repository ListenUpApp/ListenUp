package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of `book_external_ratings`: how one outside catalog rates one book, keyed by the
 * natural pair and carrying the opaque wire [syncId] that `Deleted` frames name. Server-written
 * only (no FKs — sync owns integrity, as with [BookRatingEntity]): every row arrives from the
 * server's `ExternalRatingsFetcher`, never from a client write.
 *
 * [source] stores the wire [com.calypsan.listenup.api.sync.ExternalRatingSource] enum's `name`
 * verbatim, including `"UNKNOWN"` — an older client mirrors a source it doesn't yet recognise
 * rather than dropping the row, so the average/count survive for the day this client updates and
 * learns the source's real name. Combined-score computation is free to exclude an `UNKNOWN` row;
 * this substrate only has to not crash on one.
 *
 * @property average The catalog's average, 0–5.
 * @property count How many ratings the average is over.
 * @property enabled False when an admin has switched this source off; the row stays (so
 *   re-enabling restores the score at once), but combined-score computation excludes it.
 */
@Entity(
    tableName = "book_external_ratings",
    primaryKeys = ["bookId", "source"],
    indices = [
        Index(value = ["syncId"], unique = true),
        Index(value = ["deletedAt"]),
    ],
)
internal data class BookExternalRatingEntity(
    val bookId: String,
    val source: String,
    val syncId: String,
    val average: Double,
    val count: Int,
    val enabled: Boolean,
    val revision: Long = 0,
    val deletedAt: Long? = null,
)

/**
 * Room DAO for [BookExternalRatingEntity] sync-substrate operations.
 *
 * Tombstones are soft-deletes: [BookExternalRatingEntity.deletedAt] is set to a non-null epoch-ms
 * value when a row is removed. All observation queries exclude tombstones. Read-only from the
 * client's perspective — there is no per-user write path, unlike [BookRatingDao].
 *
 * Mirrors [BookRatingDao]'s tombstone/access-gate shape, keyed by (bookId, source) instead of
 * (bookId, userId).
 */
@Dao
internal interface BookExternalRatingDao {
    @Upsert
    suspend fun upsert(entity: BookExternalRatingEntity)

    /** Live external ratings of [bookId]. */
    @Query("SELECT * FROM book_external_ratings WHERE bookId = :bookId AND deletedAt IS NULL")
    fun observeForBook(bookId: String): Flow<List<BookExternalRatingEntity>>

    /** Every live external rating row, for the library sort's combined-score computation. */
    @Query("SELECT * FROM book_external_ratings WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<BookExternalRatingEntity>>

    /**
     * Tombstone a row by its opaque wire [syncId] (SERVER-SYNC-04) — the by-identity apply for a
     * firehose `SyncEvent.Deleted` frame, whose payload has its natural pair blanked. Returns the
     * number of rows affected.
     */
    @Query(
        "UPDATE book_external_ratings SET deletedAt = :deletedAt, revision = revision + 1 " +
            "WHERE syncId = :syncId",
    )
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
    ): Int

    /** Live (non-tombstoned) sync ids — the access-gate's local truth set. */
    @Query("SELECT syncId FROM book_external_ratings WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Live sync ids whose `bookId` is one of [bookIds] — the scoped
     * `AccessDeltaPolicy.Targeted` candidate set.
     */
    @Query("SELECT syncId FROM book_external_ratings WHERE bookId IN (:bookIds) AND deletedAt IS NULL")
    suspend fun liveSyncIdsForBooks(bookIds: List<String>): List<String>

    /** Tombstone the given live rows by opaque wire sync id — the chunked access-change prune. */
    @Query("UPDATE book_external_ratings SET deletedAt = :now WHERE deletedAt IS NULL AND syncId IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Live (non-tombstoned) rows with [revision][BookExternalRatingEntity.revision] <= [max], for digest computation. */
    @Query("SELECT syncId AS id, revision FROM book_external_ratings WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the row with opaque wire [syncId], tombstones included; null when the
     * row has never been seen.
     */
    @Query("SELECT revision FROM book_external_ratings WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?

    /** Delete all rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM book_external_ratings")
    suspend fun deleteAll()
}
