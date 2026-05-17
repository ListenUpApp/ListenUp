package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

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
}
