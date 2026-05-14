package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Audio file rows owned by [BookTable]. Replaced wholesale on each aggregate
 * upsert — there is no `update single file` path.
 *
 * Composite PK `(book_id, ordinal)` preserves on-disk order. `id` is a stable
 * per-file UUID surface for clients to address.
 */
internal object BookAudioFileTable : Table("book_audio_files") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val ordinal = integer("ordinal")
    val id = varchar("id", 36)
    val filename = varchar("filename", 1024)
    val format = varchar("format", 32)
    val codec = varchar("codec", 32)
    val duration = long("duration")
    val size = long("size")
    override val primaryKey = PrimaryKey(bookId, ordinal)
}
