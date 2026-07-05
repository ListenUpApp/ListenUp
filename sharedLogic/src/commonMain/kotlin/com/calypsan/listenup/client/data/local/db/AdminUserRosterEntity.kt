package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the server `admin_user_roster` admin-only sync domain — the roster of
 * ACTIVE/PENDING_APPROVAL users backing the admin Users/pending-approval screens.
 * Server-maintained; the client only ever applies synced rows.
 *
 * Carries the canonical sync substrate ([revision], [deletedAt]) consumed by the
 * admin-user-roster sync handler for catch-up and SSE event application.
 */
@Entity(tableName = "admin_user_roster")
internal data class AdminUserRosterEntity(
    /** Primary key — equals the user's ID. */
    @PrimaryKey val id: String,
    /** User's email address. */
    val email: String,
    /** User-visible display name. */
    val displayName: String,
    /** Role, e.g. `"admin"` / `"user"`. */
    val role: String,
    /** Account status, e.g. `"active"` / `"pending_approval"`. */
    val status: String,
    /** Whether the user can share content. */
    val canShare: Boolean,
    /** Epoch-ms account creation timestamp. */
    val accountCreatedAt: Long,
    /** Monotonic server revision; 0 until the server has confirmed the row. */
    val revision: Long = 0,
    /** Epoch-ms tombstone; null while the row is live. */
    val deletedAt: Long? = null,
)

/**
 * Data Access Object for [AdminUserRosterEntity] operations.
 *
 * Manages the admin-only user roster synced from the server via the `admin_user_roster`
 * global sync domain. The primary key is the user ID ([AdminUserRosterEntity.id]).
 * This DAO is write-only from the sync layer — the client never originates changes.
 */
@Dao
internal interface AdminUserRosterDao {
    /**
     * Observe all live roster rows (tombstoned rows excluded).
     *
     * @return Flow emitting the full roster whenever it changes.
     */
    @Query("SELECT * FROM admin_user_roster WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<AdminUserRosterEntity>>

    /**
     * Insert or update a roster row.
     *
     * @param entity The row to upsert.
     */
    @Upsert
    suspend fun upsert(entity: AdminUserRosterEntity)

    /** One-shot fetch by id (incl. tombstoned), for reading the prior row inside a sync transaction. */
    @Query("SELECT * FROM admin_user_roster WHERE id = :id")
    suspend fun findById(id: String): AdminUserRosterEntity?

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     *
     * @param id The user ID whose roster row was tombstoned.
     * @param deletedAt Epoch-ms timestamp from the tombstone wire event.
     * @param revision The new server revision accompanying the tombstone.
     */
    @Query("UPDATE admin_user_roster SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** All rows (including tombstones) with [revision][AdminUserRosterEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM admin_user_roster WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM admin_user_roster WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
