package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.Activities
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * One persisted activity row, as stored. Type-specific columns are nullable / defaulted: a
 * `started_book` row fills [bookId], a `shelf_created` row fills [shelfId] + [shelfName], a
 * milestone row fills [milestoneValue] + [milestoneUnit]. The service layer joins identity,
 * ACL-filters, and projects this onto the wire `ActivityEvent`.
 *
 * [occurredAt] is the real event time; the feed orders and displays by it. [createdAt] is the
 * insertion audit timestamp retained for diagnostics.
 */
data class ActivityRow(
    val id: String,
    val userId: String,
    val type: String,
    val createdAt: Long,
    val occurredAt: Long,
    val bookId: String?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
)

/**
 * Read seam for the social-activity feed. [page] reads rows back most-recent-first for the `feed`
 * RPC; **writes go through the syncable [ActivitySyncRepository]** (via [ActivityRecorder]), so the
 * feed is a book-gated MirroredDomain that clients also receive over the sync data channel.
 *
 * [page] is intentionally raw (no identity join, no ACL filter): the [ActivityServiceImpl] layer
 * overfetches, joins `public_profiles` identity, and drops items the caller may not see, then
 * re-pages. Keeping that policy out of the store keeps this class a thin persistence seam.
 */
class ActivityRepository(
    private val db: ListenUpDatabase,
) {
    /**
     * Raw most-recent-first page (`occurred_at < before` when set, for keyset pagination); the
     * service ACL-filters and overfetches on top of this.
     */
    suspend fun page(
        before: Long?,
        limit: Int,
    ): List<ActivityRow> =
        suspendTransaction(db) {
            val rows =
                if (before != null) {
                    db.activitiesQueries.pageBefore(before, limit.toLong()).executeAsList()
                } else {
                    db.activitiesQueries.pageFirst(limit.toLong()).executeAsList()
                }
            rows.map { it.toRow() }
        }

    private fun Activities.toRow(): ActivityRow =
        ActivityRow(
            id = id,
            userId = user_id,
            type = type,
            createdAt = created_at,
            occurredAt = occurred_at,
            bookId = book_id,
            isReread = is_reread == 1L,
            durationMs = duration_ms,
            milestoneValue = milestone_value.toInt(),
            milestoneUnit = milestone_unit,
            shelfId = shelf_id,
            shelfName = shelf_name,
        )
}
