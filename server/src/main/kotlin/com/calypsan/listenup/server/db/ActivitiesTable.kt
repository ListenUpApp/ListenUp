package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Append-only social-activity log: one row per recordable user action (started a book,
 * finished a book, hit a listening milestone, created a shelf, …). Read back as a paginated,
 * most-recent-first feed via the `feed` RPC — NOT a syncable client domain, so it carries no
 * sync-discipline columns and clients never write it.
 *
 * Most columns are activity-type-specific and nullable / defaulted: a `book_started` row fills
 * `book_id`; a `shelf_created` row fills `shelf_id` + `shelf_name`; a milestone row fills
 * `milestone_value` + `milestone_unit`. The `idx_activities_created_at` index backs the
 * `created_at < before` keyset pagination the feed query performs.
 */
internal object ActivitiesTable : Table("activities") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36)
    val type = varchar("type", 32)
    val createdAt = long("created_at")
    val bookId = varchar("book_id", 36).nullable()
    val isReread = bool("is_reread").default(false)
    val durationMs = long("duration_ms").default(0L)
    val milestoneValue = integer("milestone_value").default(0)
    val milestoneUnit = varchar("milestone_unit", 16).nullable()
    val shelfId = varchar("shelf_id", 36).nullable()
    val shelfName = varchar("shelf_name", 256).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_activities_created_at", false, createdAt)
    }
}
