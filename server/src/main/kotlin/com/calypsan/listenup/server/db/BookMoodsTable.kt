package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Junction table between books and moods, with syncable substrate.
 *
 * Composite PK `(book_id, mood_id)` is the natural identity; the [id] column stores
 * the synthetic `"$bookId:$moodId"` key that the [SyncableRepository][com.calypsan.listenup.server.sync.SyncableRepository]
 * base uses for revision-cursor queries and the catch-up sync protocol.
 * [createdAt] records when the junction was first established.
 *
 * Cascade-deletes follow the book; deleting a mood requires explicit cascade via
 * [com.calypsan.listenup.server.sync.BookMoodRepository.softDeleteAllForMood].
 */
internal object BookMoodsTable : SyncableTable("book_moods") {
    val id = text("id").uniqueIndex()
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val moodId = reference("mood_id", MoodTable.id, onDelete = ReferenceOption.CASCADE)

    // createdAt, updatedAt, revision, deletedAt, clientOpId provided by SyncableTable
    override val primaryKey = PrimaryKey(bookId, moodId)

    init {
        index("idx_bmoods_mood_book", false, moodId, bookId)
    }
}
