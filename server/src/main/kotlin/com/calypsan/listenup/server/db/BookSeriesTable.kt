package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * Book series as a first-class syncable domain (Books-B1).
 *
 * Extends [SyncableTable]. Deduplicated by [normalizedName]; the wire payload
 * never carries that key. Rows are created by the scanner via
 * `SeriesRepository.resolveOrCreate`. The display [name] preserves the original
 * casing of the first writer.
 */
internal object BookSeriesTable : SyncableTable("book_series") {
    val id = varchar("id", 36)
    val normalizedName = varchar("normalized_name", 512)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_series_normalized", normalizedName)
    }
}
