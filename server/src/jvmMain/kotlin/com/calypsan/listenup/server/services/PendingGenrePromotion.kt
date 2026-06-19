package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.PendingBookGenreTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * One-time, idempotent migration of the legacy `pending_book_genres` backlog into
 * live genres.
 *
 * Earlier phases stopped *writing* the pending queue (scan/apply now auto-create
 * live genres directly), but a user's existing library still carries rows from
 * before the change. This promotion drains that backlog so the current library
 * lights up without a curator having to map every string by hand.
 *
 * For each book with pending rows it resolves every raw string through
 * [BookGenreWriter.resolveAndLink] — the same alias → normalizer → auto-create
 * cascade the scanner uses, but *additive*: it never wipes the book's
 * already-live `book_genres`. After a book's strings are resolved it drains that
 * book's pending rows ([PendingBookGenreTable.deleteAllForBook]).
 *
 * Idempotent by construction: once a book's rows are drained, a subsequent
 * [run] finds nothing pending for it, and the additive resolve uses
 * `insertIfAbsent`, so re-running never duplicates links.
 *
 * Runs once on boot after migrations + genre seeding. Does NOT drop the
 * `pending_book_genres` table — that's a later cleanup phase; this only drains
 * its rows.
 */
internal class PendingGenrePromotion(
    private val db: Database,
    private val bookGenreWriter: BookGenreWriter,
) {
    /**
     * Drains the legacy pending-genre backlog. Each book is processed in its own
     * transaction so a failure on one book can't roll back another's progress.
     * Returns the number of books promoted.
     */
    suspend fun run(): Int {
        val grouped = suspendTransaction(db) { PendingBookGenreTable.allGroupedByBook() }
        if (grouped.isEmpty()) return 0

        logger.info { "pending-genre promotion: draining backlog for ${grouped.size} book(s)" }

        var promoted = 0
        for ((bookIdStr, rawStrings) in grouped) {
            val resolved =
                runCatching {
                    suspendTransaction(db) {
                        for (raw in rawStrings) {
                            bookGenreWriter.resolveAndLink(BookId(bookIdStr), raw)
                        }
                        PendingBookGenreTable.deleteAllForBook(bookIdStr)
                    }
                }
            resolved
                .onSuccess { promoted++ }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logger.warn(e) { "pending-genre promotion: skipping book=$bookIdStr — resolve failed" }
                }
        }

        logger.info { "pending-genre promotion: promoted $promoted/${grouped.size} book(s)" }
        return promoted
    }
}
