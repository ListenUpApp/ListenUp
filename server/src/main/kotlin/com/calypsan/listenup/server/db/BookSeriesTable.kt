package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Server-internal series catalogue, deduplicated by [normalizedName].
 *
 * Not syncable in Books-A — the wire payload resolves series through this table
 * on every aggregate upsert (find-or-insert by `normalized_name`, then write
 * the [BookSeriesMembershipTable] junction row). The display [name] preserves
 * the original casing of the first writer; subsequent writers with matching
 * normalized form reuse the existing row's display [name].
 */
internal object BookSeriesTable : Table("book_series") {
    val id = varchar("id", 36)
    val normalizedName = varchar("normalized_name", 512)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_series_normalized", normalizedName)
    }
}
