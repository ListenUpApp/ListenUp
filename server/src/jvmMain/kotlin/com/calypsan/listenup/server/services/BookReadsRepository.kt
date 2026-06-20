@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.Book_reads
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/** A single completion event: who finished what, when, and how. */
data class BookReadRow(
    val userId: String,
    val bookId: String,
    val finishedAt: Long,
    val source: String,
)

/**
 * Server-only persistence for per-completion read history.
 *
 * Append-only: rows are never updated or deleted. Re-reads of the same book stack
 * as distinct rows (naturally deduplicated by [finishedAt]). Used by the readership
 * RPC surface to show persistent readers on the Book Detail screen.
 *
 * This is a **plain** (non-syncable) append-only log — there is no revision /
 * soft-delete substrate, so it persists directly over the generated
 * [ListenUpDatabase.bookReadsQueries] rather than through `SqlSyncableRepository`.
 *
 * [recordRead] is a hook target of `PlaybackPositionRepository.recordPosition`: when
 * that repo runs SQLDelight, its `recordRead` call nests as a savepoint inside the
 * open `recordPosition` transaction (same [ListenUpDatabase] connection), so the
 * completion row commits atomically with the position write — no `SQLITE_BUSY`.
 */
class BookReadsRepository(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /**
     * Append a completion row unconditionally.
     *
     * Live finishes use a fresh UUID for [id], so multiple completions by the same
     * user stack as distinct rows.
     */
    suspend fun recordRead(
        id: String,
        userId: String,
        bookId: String,
        finishedAt: Long,
        source: String,
    ) = suspendTransaction(db) {
        db.bookReadsQueries.insert(
            id = id,
            user_id = userId,
            book_id = bookId,
            finished_at = finishedAt,
            source = source,
            created_at = clock.now().toEpochMilliseconds(),
        )
    }

    /** All completions of [bookId] across all users, newest-first. */
    suspend fun finishesForBook(bookId: String): List<BookReadRow> =
        suspendTransaction(db) {
            db.bookReadsQueries
                .finishesForBook(bookId)
                .executeAsList()
                .map { it.toRow() }
        }

    /** All completion timestamps for a single user+book pair, newest-first. */
    suspend fun finishesForUserBook(
        userId: String,
        bookId: String,
    ): List<Long> =
        suspendTransaction(db) {
            db.bookReadsQueries
                .finishesForUserBook(userId, bookId)
                .executeAsList()
        }

    private fun Book_reads.toRow(): BookReadRow =
        BookReadRow(
            userId = user_id,
            bookId = book_id,
            finishedAt = finished_at,
            source = source,
        )
}
