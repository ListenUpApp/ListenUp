package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Syncable table for per-collection principal grants.
 *
 * [id] is the stable storage row identity (UUIDv7 string). A partial unique index on
 * `(collection_id, principal_type, principal_id) WHERE deleted_at IS NULL` (defined in
 * the migration) enforces one active grant per principal per collection while permitting
 * re-grants after a soft-delete. [grantedByUserId] is audit data — stored as a plain
 * text column (not `reference()`) to mirror [CollectionsTable.ownerId].
 *
 * [principalType] is the grant's subject kind. Today the only value is `"USER"` (a
 * user-share, the wire's [com.calypsan.listenup.api.sync.CollectionShareSyncPayload]);
 * GROUP principals arrive in a later phase, at which point the wire/client rename from
 * "share" to "grant" follows.
 */
internal object CollectionGrantsTable : SyncableTable("collection_grants") {
    val id = text("id")
    val collectionId = reference("collection_id", CollectionsTable.id, onDelete = ReferenceOption.CASCADE)
    val principalType = text("principal_type").default("USER")
    val principalId = text("principal_id")
    val grantedByUserId = text("granted_by_user_id")
    val permission = text("permission").default("read")
    override val primaryKey = PrimaryKey(id)
}
