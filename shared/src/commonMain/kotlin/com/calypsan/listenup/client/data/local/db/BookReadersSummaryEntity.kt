package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.calypsan.listenup.core.BookId

/**
 * Per-book aggregate summary persisting the server's authoritative reader counts.
 *
 * Written by [com.calypsan.listenup.client.data.repository.SessionRepositoryImpl.refreshBookReaders]
 * inside the same transaction that updates `user_reading_sessions` and `reader_sessions_cache`.
 * Read by the same repository's `observeBookReaders` flow so the displayed `totalReaders`
 * matches the server's canonical count rather than being derived from local cache size
 * (Finding 06 D1 — Bug 1 root cause).
 *
 * @property bookId server-assigned book id (primary key; foreign key to `books.id`).
 * @property totalReaders server's count of all users who have read or are reading this book.
 * @property totalCompletions server's count of completed reads across all users.
 * @property updatedAt epoch millis when this row was last written (for staleness diagnostics).
 */
@Entity(
    tableName = "book_readers_summary",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookReadersSummaryEntity(
    @PrimaryKey val bookId: BookId,
    val totalReaders: Int,
    val totalCompletions: Int,
    val updatedAt: Long,
)
