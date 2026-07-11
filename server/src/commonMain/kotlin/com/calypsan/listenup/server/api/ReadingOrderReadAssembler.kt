package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderBookView
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/** Chunk size for SQLite `IN` lists — stays comfortably under the 999-parameter limit. */
private const val SQLITE_IN_CHUNK = 900

/**
 * Reads the display-side projections [ReadingOrderServiceImpl] needs but the
 * syncable substrate doesn't carry: per-book title/authors/duration for
 * [ReadingOrderBookView] rows, and a reading-order owner's display name for
 * discovery labelling. Mirrors [ShelfReadAssembler].
 *
 * Split out of [ReadingOrderServiceImpl] so the service stays focused on access
 * gating and the cross-table read joins live in one cohesive place. Every method
 * opens its own SQLDelight [suspendTransaction], so callers need no surrounding
 * transaction.
 */
internal class ReadingOrderReadAssembler(
    private val sql: ListenUpDatabase,
) {
    /**
     * Builds [ReadingOrderBookView]s for [bookIds], preserving the given order.
     * Each view carries the book's title and its author display names (ordered by
     * credit ordinal). Book ids with no live row are skipped — a tombstoned book
     * never appears in a reading-order view.
     */
    suspend fun viewsFor(bookIds: List<String>): List<ReadingOrderBookView> {
        if (bookIds.isEmpty()) return emptyList()
        return suspendTransaction(sql) {
            val titles =
                bookIds
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> sql.booksQueries.selectLiveTitlesByIds(chunk).executeAsList() }
                    .associate { row -> row.id to row.title }

            val authorsByBook = authorsFor(bookIds)

            bookIds.mapNotNull { bookId ->
                val title = titles[bookId] ?: return@mapNotNull null
                ReadingOrderBookView(
                    bookId = bookId,
                    title = title,
                    authors = authorsByBook[bookId].orEmpty(),
                )
            }
        }
    }

    /**
     * Sums the total audio duration (millis) of every live book in [bookIds].
     * Tombstoned or absent books contribute zero.
     */
    suspend fun totalDurationMsFor(bookIds: List<String>): Long {
        if (bookIds.isEmpty()) return 0L
        return suspendTransaction(sql) {
            bookIds
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> sql.booksQueries.selectLiveDurationsByIds(chunk).executeAsList() }
                .sum()
        }
    }

    /** Returns [userId]'s display name, or an empty string when the user is absent. */
    suspend fun displayNameFor(userId: String): String =
        suspendTransaction(sql) {
            sql.usersQueries
                .selectDisplayNameById(userId)
                .executeAsOneOrNull()
                .orEmpty()
        }

    /** Author display names per book id, ordered by credit ordinal. Must run inside a transaction. */
    private fun authorsFor(bookIds: List<String>): Map<String, List<String>> =
        bookIds
            .chunked(SQLITE_IN_CHUNK)
            .flatMap { chunk -> sql.bookContributorsQueries.authorNamesForBooks(chunk).executeAsList() }
            .groupBy({ it.book_id }, { it.name })
}
