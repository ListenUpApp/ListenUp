package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Junction table between books and series, with optional [sequence].
 *
 * Composite PK `(book_id, series_id)`: a book belongs to a given series at most
 * once. Multi-series books (e.g. an omnibus) produce one row per series.
 * Cascade-deletes with the book; deleting a series requires explicit handling.
 */
internal object BookSeriesMembershipTable : Table("book_series_memberships") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val seriesId = reference("series_id", BookSeriesTable.id)
    val sequence = varchar("sequence", 64).nullable()
    val ordinal = integer("ordinal")
    override val primaryKey = PrimaryKey(bookId, seriesId)

    init {
        index("idx_bsm_series", false, seriesId)
    }

    /**
     * Distinct book IDs currently linked to the series identified by [id].
     * Used by Books-C1's deleteSeries cascade and updateSeries reindex flow.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun bookIdsForSeries(id: String): List<String> =
        selectAll()
            .where { seriesId eq id }
            .map { it[bookId] }
            .distinct()

    /**
     * Hard-deletes every junction row referencing [id]. Returns row count.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun deleteAllForSeries(id: String): Int = deleteWhere { seriesId eq id }

    /**
     * Re-links all `book_series_memberships` rows from [fromId] to [toId].
     * Used by [com.calypsan.listenup.server.services.SeriesServiceImpl.mergeSeries].
     *
     * Unlike `BookContributorTable.relinkContributorPreservingCredit`, series merge
     * has no `credited_as` equivalent — the canonical name change IS the intended
     * semantic ("these were always the same series").
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun relinkSeries(
        fromId: String,
        toId: String,
    ) {
        TransactionManager.current().exec(
            stmt = "UPDATE book_series_memberships SET series_id = ? WHERE series_id = ?",
            args =
                listOf(
                    TextColumnType() to toId,
                    TextColumnType() to fromId,
                ),
        )
    }
}
