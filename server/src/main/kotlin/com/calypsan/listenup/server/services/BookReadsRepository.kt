@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.BookReadsTable
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
 */
class BookReadsRepository(
    private val db: Database,
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
    ) = suspendTransaction(db) { insertRow(id, userId, bookId, finishedAt, source) }

    /**
     * Append only if [id] doesn't already exist.
     *
     * ABS import seeds use a stable deterministic id (`abs-read:<userId>:<bookId>`)
     * so that re-importing the same backup is a no-op. A second call with the same
     * [id] leaves the original [finishedAt] untouched.
     */
    suspend fun recordReadIfAbsent(
        id: String,
        userId: String,
        bookId: String,
        finishedAt: Long,
        source: String,
    ) = suspendTransaction(db) {
        val exists =
            BookReadsTable
                .selectAll()
                .where { BookReadsTable.id eq id }
                .limit(1)
                .any()
        if (!exists) insertRow(id, userId, bookId, finishedAt, source)
    }

    /** All completions of [bookId] across all users, newest-first. */
    suspend fun finishesForBook(bookId: String): List<BookReadRow> =
        suspendTransaction(db) {
            BookReadsTable
                .selectAll()
                .where { BookReadsTable.bookId eq bookId }
                .orderBy(BookReadsTable.finishedAt, SortOrder.DESC)
                .map {
                    BookReadRow(
                        userId = it[BookReadsTable.userId],
                        bookId = it[BookReadsTable.bookId],
                        finishedAt = it[BookReadsTable.finishedAt],
                        source = it[BookReadsTable.readSource],
                    )
                }
        }

    /** All completion timestamps for a single user+book pair, newest-first. */
    suspend fun finishesForUserBook(
        userId: String,
        bookId: String,
    ): List<Long> =
        suspendTransaction(db) {
            BookReadsTable
                .selectAll()
                .where { (BookReadsTable.userId eq userId) and (BookReadsTable.bookId eq bookId) }
                .orderBy(BookReadsTable.finishedAt, SortOrder.DESC)
                .map { it[BookReadsTable.finishedAt] }
        }

    private fun insertRow(
        id: String,
        userId: String,
        bookId: String,
        finishedAt: Long,
        source: String,
    ) {
        BookReadsTable.insert {
            it[BookReadsTable.id] = id
            it[BookReadsTable.userId] = userId
            it[BookReadsTable.bookId] = bookId
            it[BookReadsTable.finishedAt] = finishedAt
            it[BookReadsTable.readSource] = source
            it[BookReadsTable.createdAt] = clock.now().toEpochMilliseconds()
        }
    }
}
