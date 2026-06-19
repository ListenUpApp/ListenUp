package com.calypsan.listenup.server.sync

/**
 * A [SyncableTable] for a **per-user** domain. Adds a `user_id` column: every
 * row belongs to exactly one user, and the substrate filters every read,
 * catch-up page, and SSE event by the authenticated principal's user.
 *
 * Global domains (books, contributors, series, tags) keep extending plain
 * [SyncableTable]. Per-user domains (playback position, and the listening
 * domains of Playback P2) extend this.
 */
abstract class UserScopedSyncableTable(
    name: String,
) : SyncableTable(name) {
    /** The owning user. Indexed — every per-user query filters on it. */
    val userId = varchar("user_id", 36).index()
}
