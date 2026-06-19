package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Syncable table for per-collection user shares.
 *
 * [id] is the stable storage row identity (UUIDv7 string). A partial unique index on
 * `(collection_id, shared_with_user_id) WHERE deleted_at IS NULL` (defined in the
 * migration) enforces one active share per user per collection while permitting
 * re-shares after a soft-delete. [sharedByUserId] is audit data — stored as a plain
 * text column (not `reference()`) to mirror [CollectionsTable.ownerId].
 */
internal object CollectionSharesTable : SyncableTable("collection_shares") {
    val id = text("id")
    val collectionId = reference("collection_id", CollectionsTable.id, onDelete = ReferenceOption.CASCADE)
    val sharedWithUserId = text("shared_with_user_id").index()
    val sharedByUserId = text("shared_by_user_id")
    val permission = text("permission").default("read")
    override val primaryKey = PrimaryKey(id)
}
