package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * Contributors as a first-class syncable domain (Books-B1).
 *
 * Extends [SyncableTable] — every row carries `revision`, `created_at`,
 * `updated_at`, `deleted_at`, `client_op_id`. Deduplicated by [normalizedName];
 * the wire payload never carries that key. Rows are created by the scanner via
 * `ContributorRepository.resolveOrCreate`, which routes through the substrate so
 * each new contributor publishes a `SyncEvent`.
 *
 * B2a adds enrichment columns ([asin], [description], [imagePath], [imageBlurHash],
 * [birthDate], [deathDate], [website]) sourced from external metadata lookups.
 * All nullable — null means "not yet enriched".
 */
internal object ContributorTable : SyncableTable("contributors") {
    val id = varchar("id", 36)
    val normalizedName = varchar("normalized_name", 512)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()

    // B2a enrichment columns
    val asin = varchar("asin", 32).nullable()
    val description = text("description").nullable()
    val imagePath = varchar("image_path", 512).nullable()
    val imageBlurHash = varchar("image_blur_hash", 64).nullable()
    val birthDate = varchar("birth_date", 10).nullable()
    val deathDate = varchar("death_date", 10).nullable()
    val website = varchar("website", 512).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_contributor_normalized", normalizedName)
    }
}
