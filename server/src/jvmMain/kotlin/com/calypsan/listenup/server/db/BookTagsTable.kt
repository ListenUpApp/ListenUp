package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Junction table between books and tags, with syncable substrate.
 *
 * Composite PK `(book_id, tag_id)` is the natural identity; the [id] column stores
 * the synthetic `"$bookId:$tagId"` key that the [SyncableRepository][com.calypsan.listenup.server.sync.SyncableRepository]
 * base uses for revision-cursor queries and the catch-up sync protocol.
 * [createdAt] records when the junction was first established.
 *
 * Cascade-deletes follow the book; deleting a tag requires explicit cascade via
 * [com.calypsan.listenup.server.sync.BookTagRepository.softDeleteAllForTag].
 */
internal object BookTagsTable : SyncableTable("book_tags") {
    val id = text("id").uniqueIndex()
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val tagId = reference("tag_id", TagTable.id, onDelete = ReferenceOption.CASCADE)

    // createdAt, updatedAt, revision, deletedAt, clientOpId provided by SyncableTable
    override val primaryKey = PrimaryKey(bookId, tagId)

    init {
        index("idx_btags_tag_book", false, tagId, bookId)
    }
}
