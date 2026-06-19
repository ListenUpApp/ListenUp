package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Junction table between shelves and books, with userScoped syncable substrate.
 *
 * Composite PK `(shelf_id, book_id)` is the natural identity; [id] stores the
 * synthetic `"$shelfId:$bookId"` key that the
 * [SyncableRepository][com.calypsan.listenup.server.sync.SyncableRepository] base
 * uses for revision-cursor queries and the catch-up sync protocol. [sortOrder]
 * determines display ordering within the shelf.
 *
 * Extends [UserScopedSyncableTable] so junction rows carry `user_id` (the shelf
 * owner) and sync only to the owner. Cascade-deletes follow both the shelf and
 * the book.
 */
internal object ShelfBooksTable : UserScopedSyncableTable("shelf_books") {
    val id = text("id").uniqueIndex()
    val shelfId = reference("shelf_id", ShelvesTable.id, onDelete = ReferenceOption.CASCADE)
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(shelfId, bookId)
}
