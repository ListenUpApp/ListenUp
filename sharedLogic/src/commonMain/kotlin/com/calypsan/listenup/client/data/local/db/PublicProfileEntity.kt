package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the server `public_profiles` global sync domain — the book-agnostic
 * social roster that powers the Discover leaderboard. Server-maintained; the client
 * only ever applies synced rows (never writes locally).
 *
 * Carries the canonical sync substrate ([revision], [deletedAt]) consumed by the
 * public-profile sync handler for catch-up and SSE event application.
 */
@Entity(tableName = "public_profiles")
data class PublicProfileEntity(
    /** Primary key — equals the user's ID. */
    @PrimaryKey val id: String,
    /** User-visible display name. */
    val displayName: String,
    /** Avatar type: `"auto"` for generated avatar, `"image"` for uploaded image. */
    val avatarType: String,
    /** Cumulative listening seconds across all time. */
    val totalSecondsAllTime: Long,
    /** Cumulative listening seconds in the trailing 7-day window. */
    val totalSecondsLast7Days: Long,
    /** Cumulative listening seconds in the trailing 30-day window. */
    val totalSecondsLast30Days: Long,
    /** Cumulative listening seconds in the trailing 365-day window. */
    val totalSecondsLast365Days: Long,
    /** Number of books the user has finished. */
    val booksFinished: Int,
    /** Current listening streak in days. */
    val currentStreakDays: Int,
    /** Longest listening streak ever recorded, in days. */
    val longestStreakDays: Int,
    /** Monotonic server revision; 0 until the server has confirmed the row. */
    val revision: Long = 0,
    /** Epoch-ms tombstone; null while the row is live. */
    val deletedAt: Long? = null,
)

/**
 * Data Access Object for [PublicProfileEntity] operations.
 *
 * Manages the public social roster synced from the server via the `public_profiles`
 * global sync domain. The primary key is the user ID ([PublicProfileEntity.id]).
 * This DAO is write-only from the sync layer — the client never originates changes.
 */
@Dao
interface PublicProfileDao {
    /**
     * Observe all live public profiles (tombstoned rows excluded).
     *
     * @return Flow emitting the full public profile list whenever it changes.
     */
    @Query("SELECT * FROM public_profiles WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<PublicProfileEntity>>

    /**
     * Insert or update a public profile row.
     *
     * @param entity The profile to upsert.
     */
    @Upsert
    suspend fun upsert(entity: PublicProfileEntity)

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     *
     * @param id The user ID whose profile was tombstoned.
     * @param deletedAt Epoch-ms timestamp from the tombstone wire event.
     * @param revision The new server revision accompanying the tombstone.
     */
    @Query("UPDATE public_profiles SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** All rows (including tombstones) with [revision][PublicProfileEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM public_profiles WHERE revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>
}
