package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable

/**
 * Syncable table for per-user, user-owned shelves.
 *
 * Extends [UserScopedSyncableTable] — every row carries `user_id` (the owning
 * user) plus the standard sync-discipline columns (`revision`, `created_at`,
 * `updated_at`, `deleted_at`, `client_op_id`). The substrate filters every
 * pull/digest/firehose read by the authenticated principal's user.
 *
 * [id] is the stable storage row identity (UUIDv7 string). [isPrivate] gates
 * discovery — private shelves are invisible to non-owners; public shelves are
 * discoverable, with their book lists access-filtered at the service layer.
 */
internal object ShelvesTable : UserScopedSyncableTable("shelves") {
    val id = text("id")
    val name = text("name")
    val description = text("description").default("")
    val isPrivate = bool("is_private").default(false)
    override val primaryKey = PrimaryKey(id)
}
