@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.ActivitiesTable
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
 * Append-only store for the social-activity log. [record] inserts one row per recordable user
 * action; [page] reads them back most-recent-first for the feed. NOT a syncable client domain —
 * clients read it through the `feed` RPC and never write it.
 *
 * [page] is intentionally raw (no identity join, no ACL filter): the [ActivityServiceImpl] layer
 * overfetches, joins `public_profiles` identity, and drops items the caller may not see, then
 * re-pages. Keeping that policy out of the store keeps this class a thin persistence seam.
 */
class ActivityRepository(
    private val db: Database,
    private val clock: Clock = Clock.System,
) {
    /** Appends one activity row and returns its generated id. */
    suspend fun record(
        userId: String,
        type: String,
        bookId: String? = null,
        isReread: Boolean = false,
        durationMs: Long = 0L,
        milestoneValue: Int = 0,
        milestoneUnit: String? = null,
        shelfId: String? = null,
        shelfName: String? = null,
        occurredAt: Long? = null,
    ): String =
        suspendTransaction(db) {
            val now = clock.now().toEpochMilliseconds()
            val rowId = Uuid.random().toString()
            ActivitiesTable.insert {
                it[ActivitiesTable.id] = rowId
                it[ActivitiesTable.userId] = userId
                it[ActivitiesTable.type] = type
                it[ActivitiesTable.createdAt] = now
                it[ActivitiesTable.occurredAt] = occurredAt ?: now
                it[ActivitiesTable.bookId] = bookId
                it[ActivitiesTable.isReread] = isReread
                it[ActivitiesTable.durationMs] = durationMs
                it[ActivitiesTable.milestoneValue] = milestoneValue
                it[ActivitiesTable.milestoneUnit] = milestoneUnit
                it[ActivitiesTable.shelfId] = shelfId
                it[ActivitiesTable.shelfName] = shelfName
            }
            rowId
        }

    /**
     * Raw most-recent-first page (`occurred_at < before` when set, for keyset pagination); the
     * service ACL-filters and overfetches on top of this.
     */
    suspend fun page(
        before: Long?,
        limit: Int,
    ): List<ActivityRow> =
        suspendTransaction(db) {
            val query = ActivitiesTable.selectAll()
            val filtered = if (before != null) query.where { ActivitiesTable.occurredAt less before } else query
            filtered
                .orderBy(ActivitiesTable.occurredAt to SortOrder.DESC, ActivitiesTable.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toRow() }
        }

    private fun ResultRow.toRow() =
        ActivityRow(
            id = this[ActivitiesTable.id],
            userId = this[ActivitiesTable.userId],
            type = this[ActivitiesTable.type],
            createdAt = this[ActivitiesTable.createdAt],
            occurredAt = this[ActivitiesTable.occurredAt],
            bookId = this[ActivitiesTable.bookId],
            isReread = this[ActivitiesTable.isReread],
            durationMs = this[ActivitiesTable.durationMs],
            milestoneValue = this[ActivitiesTable.milestoneValue],
            milestoneUnit = this[ActivitiesTable.milestoneUnit],
            shelfId = this[ActivitiesTable.shelfId],
            shelfName = this[ActivitiesTable.shelfName],
        )
}
