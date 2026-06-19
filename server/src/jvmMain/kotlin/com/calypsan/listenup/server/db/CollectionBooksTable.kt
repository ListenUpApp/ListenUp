package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Junction table between collections and books, with syncable substrate.
 *
 * Composite PK `(collection_id, book_id)` is the natural identity; [id] stores the
 * synthetic `"$collectionId:$bookId"` key that the [SyncableRepository][com.calypsan.listenup.server.sync.SyncableRepository]
 * base uses for revision-cursor queries and the catch-up sync protocol.
 *
 * Cascade-deletes follow both the collection and the book.
 */
internal object CollectionBooksTable : SyncableTable("collection_books") {
    val id = text("id").uniqueIndex()
    val collectionId = reference("collection_id", CollectionsTable.id, onDelete = ReferenceOption.CASCADE)
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(collectionId, bookId)

    init {
        index("idx_cbks_book_collection", false, bookId, collectionId)
    }
}
