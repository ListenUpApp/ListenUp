package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Append-only completion log: one row per time a user finishes a book. Re-reads stack as distinct
 * rows. Server-only persistence — read by the readership RPC, never streamed over sync.
 */
internal object BookReadsTable : Table("book_reads") {
    val id = varchar("id", 80)
    val userId = varchar("user_id", 36)
    val bookId = varchar("book_id", 36)
    val finishedAt = long("finished_at")
    val readSource = varchar("source", 16) // "playback" | "import"
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_book_reads_book", false, bookId)
        index("idx_book_reads_user_book", false, userId, bookId)
    }
}
