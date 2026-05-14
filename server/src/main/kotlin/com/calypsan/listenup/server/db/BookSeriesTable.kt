package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Server-internal series catalogue, deduplicated by name (case-insensitive
 * collation in [V6__books_series.sql]).
 *
 * Not syncable in Books-A — the wire payload resolves series through this table
 * on every aggregate upsert (find-or-insert by name, then write the
 * [BookSeriesMembershipTable] junction row).
 */
internal object BookSeriesTable : Table("book_series") {
    val id = varchar("id", 36)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_series_normalized", name)
    }
}
