package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.shelf.ShelfBookView
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.UserTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** The `book_contributors.role` value that denotes an author credit. */
private const val AUTHOR_ROLE = "author"

/**
 * Reads the display-side projections [ShelfServiceImpl] needs but the syncable
 * substrate doesn't carry: per-book title/authors/duration for [ShelfBookView]
 * rows, and a shelf owner's display name for discovery labelling.
 *
 * Split out of [ShelfServiceImpl] so the service stays focused on access gating
 * and the cross-table read joins live in one cohesive place. Every method opens
 * its own `suspendTransaction`, so callers need no surrounding transaction.
 */
internal class ShelfReadAssembler(
    private val db: Database,
) {
    /**
     * Builds [ShelfBookView]s for [bookIds], preserving the given order. Each view
     * carries the book's title and its author display names (ordered by credit
     * ordinal). Book ids with no live row are skipped — a tombstoned book never
     * appears in a shelf view.
     */
    suspend fun viewsFor(bookIds: List<String>): List<ShelfBookView> {
        if (bookIds.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val titles =
                BookTable
                    .select(BookTable.id, BookTable.title)
                    .where { (BookTable.id inList bookIds) and BookTable.deletedAt.isNull() }
                    .associate { row -> row[BookTable.id] to row[BookTable.title] }

            val authorsByBook = authorsFor(bookIds)

            bookIds.mapNotNull { bookId ->
                val title = titles[bookId] ?: return@mapNotNull null
                ShelfBookView(
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
        return suspendTransaction(db) {
            BookTable
                .select(BookTable.totalDuration)
                .where { (BookTable.id inList bookIds) and BookTable.deletedAt.isNull() }
                .sumOf { row -> row[BookTable.totalDuration] }
        }
    }

    /** Returns [userId]'s display name, or an empty string when the user is absent. */
    suspend fun displayNameFor(userId: String): String =
        suspendTransaction(db) {
            UserTable
                .select(UserTable.displayName)
                .where { UserTable.id eq userId }
                .firstOrNull()
                ?.get(UserTable.displayName)
                .orEmpty()
        }

    /** Author display names per book id, ordered by credit ordinal. Must run inside a transaction. */
    private fun authorsFor(bookIds: List<String>): Map<String, List<String>> =
        (BookContributorTable innerJoin ContributorTable)
            .select(BookContributorTable.bookId, ContributorTable.name, BookContributorTable.ordinal)
            .where {
                (BookContributorTable.bookId inList bookIds) and
                    (BookContributorTable.role eq AUTHOR_ROLE)
            }.orderBy(BookContributorTable.ordinal, SortOrder.ASC)
            .groupBy({ it[BookContributorTable.bookId] }, { it[ContributorTable.name] })
}
