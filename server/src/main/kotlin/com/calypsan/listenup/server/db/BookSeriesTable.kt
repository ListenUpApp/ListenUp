package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * Book series as a first-class syncable domain (Books-B1).
 *
 * Extends [SyncableTable]. Deduplicated by [normalizedName]; the wire payload
 * never carries that key. Rows are created by the scanner via
 * `SeriesRepository.resolveOrCreate`. The display [name] preserves the original
 * casing of the first writer.
 *
 * B2a adds enrichment columns ([asin], [description], [coverPath], [coverBlurHash])
 * sourced from external metadata lookups. All nullable — null means "not yet enriched".
 */
internal object BookSeriesTable : SyncableTable("book_series") {
    val id = varchar("id", 36)
    val normalizedName = varchar("normalized_name", 512)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()
    // B2a enrichment columns
    val asin = varchar("asin", 32).nullable()
    val description = text("description").nullable()
    val coverPath = varchar("cover_path", 512).nullable()
    val coverBlurHash = varchar("cover_blur_hash", 64).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_series_normalized", normalizedName)
    }
}
