package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable

/**
 * Storage for per-user active listening sessions (Playback P3).
 *
 * Extends [UserScopedSyncableTable] — every row carries `user_id` plus the
 * standard sync-discipline columns (`revision`, `created_at`, `updated_at`,
 * `deleted_at`, `client_op_id`). One row exists per active listener; the row is
 * hard-deleted when the user finishes the book or the cleanup sweep evicts it.
 *
 * `session_id` is the primary key — a client-assigned UUID stable across reconnects.
 * There is no `(user_id, book_id)` unique constraint: a user can start a new session
 * on a different device without the old one being ended gracefully; the cleanup sweep
 * evicts the stale orphan within the staleness threshold.
 */
internal object ActiveSessionTable : UserScopedSyncableTable("active_sessions") {
    val sessionId = varchar("session_id", 36)
    val bookId = varchar("book_id", 36)
    val startedAt = long("started_at")
    override val primaryKey = PrimaryKey(sessionId)
}
