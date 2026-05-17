package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Top-level contributors, deduplicated by [normalizedName].
 *
 * Not syncable in Books-A — the wire payload resolves contributors through this
 * table on every aggregate upsert (find-or-insert by `normalized_name`, then
 * write the [BookContributorTable] junction row).
 */
internal object ContributorTable : Table("contributors") {
    val id = varchar("id", 36)
    val normalizedName = varchar("normalized_name", 512)
    val name = varchar("name", 512)
    val sortName = varchar("sort_name", 512).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_contributor_normalized", normalizedName)
    }
}
