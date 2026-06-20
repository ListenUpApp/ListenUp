@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.Activities
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
 *
 * This is a **plain** (non-syncable) append-only log — there is no revision / soft-delete
 * substrate, so it persists directly over the generated [ListenUpDatabase.activitiesQueries]
 * rather than through `SqlSyncableRepository`. [record] is a hook target of the playback and
 * listening-event repos (via [ActivityRecorder]); when those run SQLDelight, the insert nests
 * as a savepoint inside the open event transaction (same [ListenUpDatabase] connection) — no
 * `SQLITE_BUSY`.
 */
class ActivityRepository(
    private val db: ListenUpDatabase,
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
            db.activitiesQueries.insert(
                id = rowId,
                user_id = userId,
                type = type,
                created_at = now,
                occurred_at = occurredAt ?: now,
                book_id = bookId,
                is_reread = if (isReread) 1L else 0L,
                duration_ms = durationMs,
                milestone_value = milestoneValue.toLong(),
                milestone_unit = milestoneUnit,
                shelf_id = shelfId,
                shelf_name = shelfName,
            )
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
