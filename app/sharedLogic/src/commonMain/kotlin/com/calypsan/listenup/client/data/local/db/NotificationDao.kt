package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [NotificationEntity] inbox sync operations (Notifications — Room v7).
 *
 * Tombstones are soft-deletes via [NotificationEntity.deletedAt]; observation queries exclude
 * tombstoned rows so the inbox reactively reflects removals. The read state is a local-first
 * stamp: [markRead] writes optimistically and the guard makes replays no-ops.
 */
@Dao
internal interface NotificationDao {
    /**
     * Insert or update an inbox row. Replaces on conflict using the primary key.
     */
    @Upsert
    suspend fun upsert(entity: NotificationEntity)

    /**
     * Tombstone an inbox row: set [NotificationEntity.deletedAt] and its
     * [NotificationEntity.revision] to the server-authoritative [revision], so a replayed
     * `Deleted` frame is a true no-op. Returns the number of rows affected (0 when [id] matches
     * no local row — a graceful no-op the caller logs).
     */
    @Query("UPDATE notifications SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun tombstone(
        id: String,
        deletedAt: Long,
        revision: Long,
    ): Int

    /**
     * Stamp [NotificationEntity.readAt] on an unread row. The `readAt IS NULL` guard makes the
     * optimistic local write idempotent against replays — a second stamp never moves the first.
     */
    @Query("UPDATE notifications SET readAt = :readAt WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(
        id: String,
        readAt: Long,
    )

    /**
     * Observe all live (non-tombstoned) inbox rows, newest first.
     */
    @Query("SELECT * FROM notifications WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    /**
     * Observe the count of live, unread inbox rows — the badge number.
     */
    @Query("SELECT COUNT(*) FROM notifications WHERE deletedAt IS NULL AND readAt IS NULL")
    fun observeUnreadCount(): Flow<Int>

    /** Live rows with [revision][NotificationEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id, revision FROM notifications WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when never seen. */
    @Query("SELECT revision FROM notifications WHERE id = :id")
    suspend fun revisionOf(id: String): Long?

    /**
     * Delete all inbox rows (used in tests and full re-sync scenarios).
     */
    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}
