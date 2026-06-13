package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable

/**
 * Storage for per-user listening-span records (Playback P2).
 *
 * Extends [UserScopedSyncableTable] — every row carries `user_id` plus the
 * standard sync-discipline columns (`revision`, `created_at`, `updated_at`,
 * `deleted_at`, `client_op_id`). Append-only: one row per closed playback span;
 * rows are never mutated after creation (only revision/updatedAt advance on an
 * idempotent re-upsert from the pending-op queue).
 */
internal object ListeningEventTable : UserScopedSyncableTable("listening_events") {
    val id = varchar("id", 64) // 64, not 36: ABS-imported events use an "abs:<uuid>" id (40 chars); see SessionConverter.
    val bookId = varchar("book_id", 36)
    val startPositionMs = long("start_position_ms")
    val endPositionMs = long("end_position_ms")
    val startedAt = long("started_at")
    val endedAt = long("ended_at")
    val playbackSpeed = float("playback_speed")
    val tz = varchar("tz", 64)
    val deviceLabel = varchar("device_label", 128).nullable()
    override val primaryKey = PrimaryKey(id)
}
