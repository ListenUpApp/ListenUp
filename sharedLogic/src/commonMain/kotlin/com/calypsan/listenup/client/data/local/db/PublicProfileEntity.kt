package com.calypsan.listenup.client.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the server `public_profiles` global sync domain — the book-agnostic
 * social roster that powers the Discover leaderboard, and the SINGLE source every avatar
 * render resolves from. Server-maintained: the client applies synced rows and does not
 * originate changes — with ONE carve-out. On the caller's OWN avatar upload / revert-to-auto,
 * [com.calypsan.listenup.client.data.repository.ProfileEditRepositoryImpl] optimistically writes
 * this user's own row ([avatarType] + [avatarUpdatedAt]) so the observed avatar re-emits before
 * the sync round-trip. The eventual SSE echo (higher [revision], ServerWins) replaces it without
 * regressing the version — the server stamped the same [avatarUpdatedAt].
 *
 * Carries the canonical sync substrate ([revision], [deletedAt]) consumed by the
 * public-profile sync handler for catch-up and SSE event application.
 */
@Entity(tableName = "public_profiles")
internal data class PublicProfileEntity(
    /** Primary key — equals the user's ID. */
    @PrimaryKey val id: String,
    /** User-visible display name. */
    val displayName: String,
    /** Avatar type: `"auto"` for generated avatar, `"image"` for uploaded image. */
    val avatarType: String,
    /** User-visible tagline/bio. */
    val tagline: String? = null,
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
    /** Distinct books finished in the trailing 7-day window (windowed leaderboard metric). */
    @ColumnInfo(defaultValue = "0") val booksFinishedLast7Days: Int = 0,
    /** Distinct books finished in the trailing 30-day window. */
    @ColumnInfo(defaultValue = "0") val booksFinishedLast30Days: Int = 0,
    /** Distinct books finished in the trailing 365-day window. */
    @ColumnInfo(defaultValue = "0") val booksFinishedLast365Days: Int = 0,
    /** Longest consecutive listening-day run within the trailing 7-day window. */
    @ColumnInfo(defaultValue = "0") val longestStreakLast7Days: Int = 0,
    /** Longest consecutive listening-day run within the trailing 30-day window. */
    @ColumnInfo(defaultValue = "0") val longestStreakLast30Days: Int = 0,
    /** Longest consecutive listening-day run within the trailing 365-day window. */
    @ColumnInfo(defaultValue = "0") val longestStreakLast365Days: Int = 0,
    /** Epoch-ms the avatar bytes last changed server-side; the avatar re-download signal + Coil cache-buster. */
    @ColumnInfo(defaultValue = "0") val avatarUpdatedAt: Long = 0,
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
internal interface PublicProfileDao {
    /**
     * Observe all live public profiles (tombstoned rows excluded).
     *
     * @return Flow emitting the full public profile list whenever it changes.
     */
    @Query("SELECT * FROM public_profiles WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<PublicProfileEntity>>

    /** Observe a single public profile by user ID (live rows only); null when tombstoned/absent. */
    @Query("SELECT * FROM public_profiles WHERE id = :userId AND deletedAt IS NULL")
    fun observeById(userId: String): Flow<PublicProfileEntity?>

    /**
     * Insert or update a public profile row.
     *
     * @param entity The profile to upsert.
     */
    @Upsert
    suspend fun upsert(entity: PublicProfileEntity)

    /** One-shot fetch by id (incl. tombstoned), for reading the prior row inside a sync transaction. */
    @Query("SELECT * FROM public_profiles WHERE id = :userId")
    suspend fun findById(userId: String): PublicProfileEntity?

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
    @Query("SELECT id AS id, revision FROM public_profiles WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM public_profiles WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
