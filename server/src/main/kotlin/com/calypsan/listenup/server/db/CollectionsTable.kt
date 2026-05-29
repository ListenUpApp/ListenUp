package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Syncable table for user-owned collections.
 *
 * [id] is the stable storage row identity (UUIDv7 string). Collections gate access to
 * their member books — uncollected books are public; collected books are gated behind
 * the collection's [isGlobalAccess] flag or an explicit share in [CollectionSharesTable].
 *
 * [isInbox] marks the system-managed inbox collection for a library — at most one live
 * inbox per library is enforced by a partial unique index on [libraryId] in the migration.
 * [ownerId] references `users.id`; stored as a plain text column (not `reference()`) so
 * that test fixtures can insert collections without seeding a user row.
 */
internal object CollectionsTable : SyncableTable("collections") {
    val id = text("id")
    val libraryId = reference("library_id", LibraryTable.id, onDelete = ReferenceOption.CASCADE)
    val ownerId = text("owner_id").index()
    val name = text("name")
    val isInbox = bool("is_inbox").default(false)
    val isGlobalAccess = bool("is_global_access").default(false)
    override val primaryKey = PrimaryKey(id)
}
