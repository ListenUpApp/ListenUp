package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Raw scanner genre strings that didn't resolve via [GenreAliasTable], keyed
 * back to the originating book. The curator screen aggregates rows by raw
 * string and lets the operator map each to a canonical genre — the mapping
 * step both creates an alias (so future scans resolve immediately) and links
 * every book that contributed the string to the chosen genre.
 *
 * Idempotency on rescan: the scanner wipes-then-rewrites rows for the book
 * via [replacePendingForBook], so a book whose `(book.opf|metadata.json)`
 * lost a raw string no longer appears as the source for it.
 *
 * Cascades on book hard-delete via FK; no cascade on genre delete is needed
 * because rows are not linked to genres (they exist precisely because no
 * genre could be resolved).
 */
internal object PendingBookGenreTable : Table("pending_book_genres") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val rawString = varchar("raw_string", 200)
    val firstSeenAt = long("first_seen_at")
    override val primaryKey = PrimaryKey(bookId, rawString)

    init {
        index("idx_pending_book_genres_raw", false, rawString)
    }

    /**
     * Inserts a `(bookId, rawString)` row at [firstSeenAt]; silently no-ops on
     * PK conflict so re-emitting the same pair doesn't change the recorded
     * first-seen timestamp.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun addPending(
        bookId: String,
        rawString: String,
        firstSeenAt: Long,
    ) {
        insertIgnore {
            it[this@PendingBookGenreTable.bookId] = bookId
            it[this@PendingBookGenreTable.rawString] = rawString.trim()
            it[this@PendingBookGenreTable.firstSeenAt] = firstSeenAt
        }
    }

    /**
     * Idempotent rescan primitive: hard-deletes every pending row for [bookId],
     * then re-inserts one row per case-insensitive-distinct entry in
     * [rawStrings] at [firstSeenAt]. After this call, the table reflects
     * exactly the unmapped strings for the book's current scan.
     *
     * De-duplication is case-insensitive to match the `genre_aliases` lookup
     * semantics — two scan strings that differ only by case ("Sci-Fi" vs
     * "sci-fi") should not yield two pending rows.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun replacePendingForBook(
        bookId: String,
        rawStrings: List<String>,
        firstSeenAt: Long,
    ) {
        deleteWhere { this@PendingBookGenreTable.bookId eq bookId }
        for (raw in rawStrings.distinctBy { it.trim().lowercase() }) {
            addPending(bookId, raw, firstSeenAt)
        }
    }

    /**
     * One row per distinct raw string in the queue, with the count of books
     * contributing it and the earliest first-seen timestamp across those books.
     * Drives the curator screen's "unmapped strings" list, ordered by
     * book-count descending (most-impactful strings first) then by raw string
     * ascending.
     *
     * Raw SQL: Exposed's `groupBy { ... }.having { ... }` is awkward for
     * `GROUP BY` over a string column with `COUNT(DISTINCT)`; the JDBC form
     * is the precise tool here.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun aggregateByString(): List<UnmappedAggregate> {
        val results = mutableListOf<UnmappedAggregate>()
        TransactionManager.current().exec(
            stmt =
                "SELECT raw_string, COUNT(DISTINCT book_id) AS book_count, " +
                    "MIN(first_seen_at) AS first_seen " +
                    "FROM pending_book_genres " +
                    "GROUP BY raw_string " +
                    "ORDER BY book_count DESC, raw_string ASC",
        ) { rs ->
            while (rs.next()) {
                results.add(
                    UnmappedAggregate(
                        rawString = rs.getString("raw_string"),
                        bookCount = rs.getInt("book_count"),
                        firstSeenAt = rs.getLong("first_seen"),
                    ),
                )
            }
        }
        return results
    }

    /**
     * Every pending row grouped by book: `bookId -> its raw strings`. Drives the
     * one-time legacy backlog promotion ([com.calypsan.listenup.server.services.PendingGenrePromotion]),
     * which resolves each book's unmapped strings into live genres then drains the
     * book's rows. Empty map when the queue is empty.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun allGroupedByBook(): Map<String, List<String>> =
        selectAll()
            .map { it[bookId] to it[rawString] }
            .groupBy({ it.first }, { it.second })

    /**
     * Hard-deletes every pending row for [bookId]. Returns the row count. Called
     * by the legacy backlog promotion after a book's strings have been resolved
     * and linked, draining its pending rows so the one-time migration is
     * idempotent.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun deleteAllForBook(bookId: String): Int = deleteWhere { this@PendingBookGenreTable.bookId eq bookId }

    /**
     * Distinct book ids that contributed [rawString] to the unmapped queue.
     * Used by the curator-mapping flow: when an operator maps "Sci-Fi" to
     * genre X, every book in this list gets a `book_genres` row added for X.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun bookIdsByRawString(rawString: String): List<String> =
        selectAll()
            .where { this@PendingBookGenreTable.rawString eq rawString.trim() }
            .map { it[bookId] }
            .distinct()

    /**
     * Hard-deletes every pending row for [rawString]. Returns the row count.
     * Called after a curator maps the string to a canonical genre — the
     * mapping turns the rows into `book_genres` + `genre_aliases` entries,
     * so the pending rows are no longer needed.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun deleteAllForRawString(rawString: String): Int =
        deleteWhere { this@PendingBookGenreTable.rawString eq rawString.trim() }

    /**
     * Per-raw-string aggregate row returned by [aggregateByString].
     *
     * @property rawString the raw scanner string awaiting curator mapping
     * @property bookCount how many distinct books contributed this string
     * @property firstSeenAt earliest epoch-millis timestamp across those books
     */
    data class UnmappedAggregate(
        val rawString: String,
        val bookCount: Int,
        val firstSeenAt: Long,
    )
}
