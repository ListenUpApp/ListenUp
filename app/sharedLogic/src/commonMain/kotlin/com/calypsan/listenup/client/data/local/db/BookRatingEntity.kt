package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of `book_ratings`: one listener's rating of one book, keyed by the natural pair and
 * carrying the opaque wire [syncId] that `Deleted` frames name. No FKs — sync owns integrity, as
 * with [BookMoodEntity].
 *
 * @property halfStars 2..10 half-star units.
 * @property note The listener's note, or null.
 */
@Entity(
    tableName = "book_ratings",
    primaryKeys = ["bookId", "userId"],
    indices = [
        Index(value = ["syncId"], unique = true),
        Index(value = ["deletedAt"]),
    ],
)
internal data class BookRatingEntity(
    val bookId: String,
    val userId: String,
    val syncId: String,
    val halfStars: Int,
    val note: String?,
    val ratedAt: Long,
    val updatedAt: Long,
    val revision: Long = 0,
    val deletedAt: Long? = null,
)

/** Per-book listener average, for library sort and the Book Detail summary. */
internal data class BookRatingAverageRow(
    val bookId: String,
    val averageHalfStars: Double,
    val ratingCount: Int,
)

/**
 * Room DAO for [BookRatingEntity] sync-substrate operations.
 *
 * Tombstones are soft-deletes: [BookRatingEntity.deletedAt] is set to a non-null epoch-ms value
 * when a rating is cleared. All observation queries exclude tombstones.
 *
 * Mirrors [BookMoodDao] — ratings share the junction stack's shape and sync discipline, keyed by
 * (bookId, userId) instead of (bookId, moodId).
 */
@Dao
internal interface BookRatingDao {
    @Upsert
    suspend fun upsert(entity: BookRatingEntity)

    @Query("SELECT * FROM book_ratings WHERE bookId = :bookId AND userId = :userId LIMIT 1")
    suspend fun find(
        bookId: String,
        userId: String,
    ): BookRatingEntity?

    /** Live ratings of [bookId], most recently edited first. */
    @Query("SELECT * FROM book_ratings WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<BookRatingEntity>>

    /** Average and count per book with at least one live rating. */
    @Query(
        "SELECT bookId, AVG(halfStars) AS averageHalfStars, COUNT(*) AS ratingCount " +
            "FROM book_ratings WHERE deletedAt IS NULL GROUP BY bookId",
    )
    fun observeAverages(): Flow<List<BookRatingAverageRow>>

    @Query(
        "UPDATE book_ratings SET deletedAt = :deletedAt, revision = revision + 1 " +
            "WHERE bookId = :bookId AND userId = :userId",
    )
    suspend fun tombstone(
        bookId: String,
        userId: String,
        deletedAt: Long,
    )

    /**
     * Tombstone a rating row by its opaque wire [syncId] (SERVER-SYNC-04) — the by-identity
     * counterpart to [tombstone], used when applying a `SyncEvent.Deleted` frame whose payload
     * has its natural pair blanked. Returns the number of rows affected.
     */
    @Query("UPDATE book_ratings SET deletedAt = :deletedAt, revision = revision + 1 WHERE syncId = :syncId")
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
    ): Int

    /** Live (non-tombstoned) rating sync ids — the access-gate's local truth set. */
    @Query("SELECT syncId FROM book_ratings WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Live rating sync ids whose `bookId` is one of [bookIds] — the scoped
     * `AccessDeltaPolicy.Targeted` candidate set.
     */
    @Query("SELECT syncId FROM book_ratings WHERE bookId IN (:bookIds) AND deletedAt IS NULL")
    suspend fun liveSyncIdsForBooks(bookIds: List<String>): List<String>

    /** Tombstone the given live rating rows by opaque wire sync id — the chunked access-change prune. */
    @Query("UPDATE book_ratings SET deletedAt = :now WHERE deletedAt IS NULL AND syncId IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Live (non-tombstoned) rows with [revision][BookRatingEntity.revision] <= [max], for digest computation. */
    @Query("SELECT syncId AS id, revision FROM book_ratings WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the rating row with opaque wire [syncId], tombstones included;
     * null when the row has never been seen.
     */
    @Query("SELECT revision FROM book_ratings WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?

    /** Delete all rating rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM book_ratings")
    suspend fun deleteAll()
}
