package com.calypsan.listenup.server.sync

import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Service-layer reindexer for the `book_search` FTS5 table.
 *
 * `book_search` is a contentless FTS5 table with `contentless_delete=1` (see V9/V21
 * migrations). The application layer owns all FTS population — no triggers keep
 * `book_search` in sync. [BookSearchReindexer] is the sole writer for the entire
 * FTS5 row (title, subtitle, description, contributor_names, series_names, tags).
 *
 * Each call reads the live (non-tombstoned) state for the target book from all
 * source tables, then uses the FTS5 `DELETE` + re-insert idiom required by
 * `contentless_delete=1` tables.
 *
 * **Why service-layer and not a SQL trigger?** The spec chose application-layer
 * reindexing for testability and decoupling — a trigger cannot join across
 * `book_tags → tags → book_search_map` cleanly, whereas this class does it in
 * readable, testable Kotlin.
 */
class BookSearchReindexer(
    private val bookTagRepository: BookTagRepository,
    private val tagRepository: TagRepository,
    private val db: Database,
) {
    /**
     * Recomputes the `book_search` FTS5 row for [bookId] from the live source tables.
     *
     * `book_search` is a contentless FTS5 table with `contentless_delete=1` (V9/V21);
     * the application layer owns all FTS population. This method reads the current state
     * from `books`, `book_contributors → contributors`, `book_series_memberships →
     * book_series`, and `book_tags → tags`, then DELETE + re-INSERTs the FTS row (the
     * FTS5 idiom for `contentless_delete=1`).
     *
     * Safe to call when the book has no `book_search_map` row (never scanned, or
     * tombstoned) — the SQL is a no-op in those cases.
     */
    suspend fun reindexBook(bookId: String) {
        // Resolve live tag names for this book.
        val junctions = bookTagRepository.findAllForBook(bookId)
        val tagNames =
            junctions.mapNotNull { junc ->
                tagRepository.findById(junc.tagId)?.name
            }
        val tagsValue = tagNames.joinToString(" ")

        suspendTransaction(db) {
            val tx = TransactionManager.current()
            // Resolve the FTS5 rowid via book_search_map. If absent, nothing to do.
            val rowid = resolveRowid(tx, bookId) ?: return@suspendTransaction

            // Read the existing FTS content from the source tables so we can re-insert
            // all columns — FTS5 contentless tables don't store the original text.
            val existing = readExistingFtsRow(tx, rowid)

            // FTS5 contentless_delete=1: delete old tokens first, then re-insert full row.
            tx.exec("DELETE FROM book_search WHERE rowid = $rowid")
            tx.exec(
                stmt =
                    "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                        "VALUES ($rowid, ?, ?, ?, ?, ?, ?)",
                args =
                    listOf(
                        TextColumnType() to (existing?.title ?: ""),
                        TextColumnType() to (existing?.subtitle ?: ""),
                        TextColumnType() to (existing?.description ?: ""),
                        TextColumnType() to (existing?.contributorNames ?: ""),
                        TextColumnType() to (existing?.seriesNames ?: ""),
                        TextColumnType() to tagsValue,
                    ),
            )
        }
    }

    /**
     * Reindexes every book that currently has a live junction row for [tagId].
     * Called after [com.calypsan.listenup.server.api.TagServiceImpl.renameTag]
     * and [com.calypsan.listenup.server.api.TagServiceImpl.deleteTag].
     */
    suspend fun reindexAllBooksForTag(tagId: String) {
        val bookIds = bookTagRepository.findBookIdsForTag(tagId)
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    /**
     * Reindexes every book that currently has a live junction row referencing
     * [contributorId]. Called by
     * [com.calypsan.listenup.server.api.ContributorServiceImpl.updateContributor]
     * (on name / sortName change) and `deleteContributor` (after junction hard-delete).
     *
     * Reads affected book IDs from [com.calypsan.listenup.server.db.BookContributorTable].
     * Each per-book reindex re-pulls `contributor_names` from source via [reindexBook].
     */
    suspend fun reindexAllBooksForContributor(contributorId: String) {
        val bookIds = suspendTransaction(db) {
            com.calypsan.listenup.server.db.BookContributorTable.bookIdsForContributor(contributorId)
        }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    /**
     * Mirror for series — reindexes every book that currently has a live
     * junction row referencing [seriesId].
     */
    suspend fun reindexAllBooksForSeries(seriesId: String) {
        val bookIds = suspendTransaction(db) {
            com.calypsan.listenup.server.db.BookSeriesMembershipTable.bookIdsForSeries(seriesId)
        }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveRowid(
        tx: org.jetbrains.exposed.v1.jdbc.JdbcTransaction,
        bookId: String,
    ): Int? {
        var rowid: Int? = null
        tx.exec(
            stmt = "SELECT rowid FROM book_search_map WHERE book_id = ?",
            args = listOf(TextColumnType() to bookId),
        ) { rs ->
            if (rs.next()) rowid = rs.getInt("rowid")
        }
        return rowid
    }

    private data class FtsRow(
        val title: String,
        val subtitle: String,
        val description: String,
        val contributorNames: String,
        val seriesNames: String,
    )

    /**
     * Reads the current book columns from the content-source tables
     * (`books` + junction tables) rather than from `book_search` (which is
     * contentless and doesn't store the original text).
     */
    private fun readExistingFtsRow(
        tx: org.jetbrains.exposed.v1.jdbc.JdbcTransaction,
        rowid: Int,
    ): FtsRow? {
        var result: FtsRow? = null
        tx.exec(
            stmt =
                "SELECT b.title, COALESCE(b.subtitle, '') AS subtitle, " +
                    "COALESCE(b.description, '') AS description, " +
                    "COALESCE((SELECT GROUP_CONCAT(c.name, ', ') " +
                    " FROM book_contributors bc " +
                    " JOIN contributors c ON c.id = bc.contributor_id " +
                    " WHERE bc.book_id = b.id), '') AS contributor_names, " +
                    "COALESCE((SELECT GROUP_CONCAT(bs.name, ', ') " +
                    " FROM book_series_memberships bsm " +
                    " JOIN book_series bs ON bs.id = bsm.series_id " +
                    " WHERE bsm.book_id = b.id), '') AS series_names " +
                    "FROM book_search_map m " +
                    "JOIN books b ON b.id = m.book_id " +
                    "WHERE m.rowid = ?",
            args = listOf(IntegerColumnType() to rowid),
        ) { rs ->
            if (rs.next()) {
                result =
                    FtsRow(
                        title = rs.getString("title") ?: "",
                        subtitle = rs.getString("subtitle") ?: "",
                        description = rs.getString("description") ?: "",
                        contributorNames = rs.getString("contributor_names") ?: "",
                        seriesNames = rs.getString("series_names") ?: "",
                    )
            }
        }
        return result
    }
}
