package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Book ↔ Genre junction. Cross-user: every user sees the same genre set on a
 * given book (genres are curator-managed taxonomy, not per-user state).
 *
 * Cascades on either side via `ON DELETE CASCADE` (V23 FK constraints), so a
 * book hard-delete or genre hard-delete removes the junction rows automatically.
 * No syncable substrate — book updates carry their genre set inline via
 * `BookSyncPayload.genres`, so the junction doesn't need its own revision cursor.
 */
internal object BookGenreTable : Table("book_genres") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genre_id", GenreTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(bookId, genreId)

    init {
        index("idx_book_genres_genre", false, genreId)
    }

    /**
     * Returns the genre ids currently linked to [bookId]. Order is unspecified
     * (callers that need a stable order should sort by genre name/path on read).
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun genresForBook(bookId: String): List<String> =
        selectAll()
            .where { this@BookGenreTable.bookId eq bookId }
            .map { it[genreId] }

    /**
     * Returns up to [limit] book ids linked to [genreId]. No ordering guarantee.
     * Used by the curator screen and by reindex-after-genre-rename flows.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun booksForGenre(
        genreId: String,
        limit: Int,
    ): List<String> =
        selectAll()
            .where { this@BookGenreTable.genreId eq genreId }
            .limit(limit)
            .map { it[bookId] }

    /**
     * Returns up to [limit] distinct book ids linked to any *live* genre whose
     * materialized path equals [pathPrefix] or starts with `pathPrefix + "/"`.
     * Used to drive the browse-by-genre screen including descendant rollup.
     *
     * Raw SQL: the join + collision-safe path predicate (`path = ? OR path LIKE ? || '/%'`)
     * is clearer expressed inline than via Exposed DSL `innerJoin`.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun booksForGenrePrefix(
        pathPrefix: String,
        limit: Int,
    ): List<String> {
        val results = mutableListOf<String>()
        TransactionManager.current().exec(
            stmt =
                "SELECT DISTINCT bg.book_id FROM book_genres bg " +
                    "JOIN genres g ON g.id = bg.genre_id " +
                    "WHERE g.deleted_at IS NULL AND (g.path = ? OR g.path LIKE ? || '/%') " +
                    "LIMIT ?",
            args =
                listOf(
                    TextColumnType() to pathPrefix,
                    TextColumnType() to pathPrefix,
                    IntegerColumnType() to limit,
                ),
        ) { rs ->
            while (rs.next()) results.add(rs.getString(1))
        }
        return results
    }

    /**
     * Atomically replaces the genre set for [bookId] with [genreIds]: hard-deletes
     * every existing junction row for the book, then inserts one row per distinct
     * id in [genreIds]. The replace shape mirrors
     * [ContributorAliasTable.replaceForContributor] — wire payload carries an
     * embedded array, so the write side mirrors that array verbatim.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun relinkBookGenres(
        bookId: String,
        genreIds: List<String>,
    ) {
        deleteAllForBook(bookId)
        for (gid in genreIds.distinct()) {
            insertIgnore {
                it[BookGenreTable.bookId] = bookId
                it[BookGenreTable.genreId] = gid
            }
        }
    }

    /**
     * Hard-deletes every junction row referencing [bookId]. Returns the row
     * count. Used by [relinkBookGenres] and by the book hard-delete cascade
     * (though the FK does the work in that case — this helper is for the
     * application-side cascade in `setBookGenres`).
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun deleteAllForBook(bookId: String): Int = deleteWhere { this@BookGenreTable.bookId eq bookId }

    /**
     * Hard-deletes every junction row referencing [genreId]. Returns the row
     * count. Used by genre hard-delete and merge cascade flows.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun deleteAllForGenre(genreId: String): Int = deleteWhere { this@BookGenreTable.genreId eq genreId }

    /**
     * Distinct book ids currently linked to [genreId]. Used to enumerate the
     * affected books before a genre rename / merge so the search-index
     * reindex can run for each.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun bookIdsForGenre(genreId: String): List<String> =
        selectAll()
            .where { this@BookGenreTable.genreId eq genreId }
            .map { it[bookId] }
            .distinct()

    /**
     * Merge primitive: re-links every junction row from [fromId] to [toId],
     * skipping rows where the book is already linked to [toId] (so the merge
     * never duplicates rows that would violate the composite PK).
     *
     * Implementation: SQLite-specific `INSERT OR IGNORE ... SELECT` for the
     * additive half, followed by a plain `DELETE` of the source rows. This is
     * the standard merge-without-PK-collision shape; Exposed v1 DSL doesn't
     * express `INSERT OR IGNORE ... SELECT` cleanly, so raw SQL is the precise
     * tool here.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun relinkGenre(
        fromId: String,
        toId: String,
    ) {
        val tx = TransactionManager.current()
        tx.exec(
            stmt =
                "INSERT OR IGNORE INTO book_genres (book_id, genre_id) " +
                    "SELECT book_id, ? FROM book_genres WHERE genre_id = ?",
            args =
                listOf(
                    TextColumnType() to toId,
                    TextColumnType() to fromId,
                ),
        )
        tx.exec(
            stmt = "DELETE FROM book_genres WHERE genre_id = ?",
            args = listOf(TextColumnType() to fromId),
        )
    }

    /**
     * Inserts `(bookId, genreId)` if no such row exists; silently no-ops on
     * PK conflict. Used by the unmapped-string mapping flow, which links a
     * batch of books to a newly-resolved genre but cannot assume the books
     * weren't already linked.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun insertIfAbsent(
        bookId: String,
        genreId: String,
    ) {
        insertIgnore {
            it[BookGenreTable.bookId] = bookId
            it[BookGenreTable.genreId] = genreId
        }
    }
}
