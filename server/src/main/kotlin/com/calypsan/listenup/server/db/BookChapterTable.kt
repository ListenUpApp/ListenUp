package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Chapter rows owned by [BookTable]. Replaced wholesale on each aggregate
 * upsert — there is no `update single chapter` path.
 *
 * Composite PK `(book_id, ordinal)` so chapter order is part of identity:
 * inserting chapter 3 at position 5 produces a new row, not a move.
 * `id` is a stable per-chapter UUID surface for clients to address.
 */
internal object BookChapterTable : Table("book_chapters") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val ordinal = integer("ordinal")
    val id = varchar("id", 36)
    val title = varchar("title", 1024)
    val duration = long("duration")
    val startTime = long("start_time")
    override val primaryKey = PrimaryKey(bookId, ordinal)
}
