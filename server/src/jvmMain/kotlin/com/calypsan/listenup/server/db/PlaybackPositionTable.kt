package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable

/**
 * Storage for per-user playback positions (Playback P1).
 *
 * Extends [UserScopedSyncableTable] — every row carries `user_id` plus the
 * standard sync-discipline columns (`revision`, `created_at`, `updated_at`,
 * `deleted_at`, `client_op_id`). One row per `(user_id, book_id)` pair,
 * enforced by the unique index. The row is updated in place on every
 * position update; `lastPlayedAt` is the conflict key — a write with a stale
 * `lastPlayedAt` is silently dropped by the repository.
 */
internal object PlaybackPositionTable : UserScopedSyncableTable("playback_positions") {
    val id = varchar("id", 36)
    val bookId = varchar("book_id", 36)
    val positionMs = long("position_ms")
    val lastPlayedAt = long("last_played_at")
    val finished = bool("finished").default(false)
    val playbackSpeed = float("playback_speed").default(1.0f)
    val currentChapterId = varchar("current_chapter_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_playback_position_user_book", userId, bookId)
    }
}
