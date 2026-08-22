package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.NotificationSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Notifications
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for the `notifications` per-user inbox.
 *
 * `userScoped = true` — rows carry the owning `user_id` and sync only to that user: the firehose
 * matches [BusEvent.userId], and pull/digest route through the substrate's `*ForUser` variants.
 * There is deliberately NO `ACCESS_FILTERS` entry and no client `AccessGate`: `pullByIds` on a
 * userScoped domain returns an empty page by construction (pinned by NotificationUserScopingTest),
 * which is the leak-proof property this domain relies on.
 *
 * Service-layer helpers beyond the base substrate — both route through the substrate's
 * [upsert]/[softDelete] so every mutation bumps a revision and publishes to the change bus:
 *  - [markRead] — stamp a notification read for its owner (first read wins), ownership fail-closed
 *  - [pruneToRetention] — tombstone the oldest live rows beyond a per-user retention cap
 */
class NotificationRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<NotificationSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.NOTIFICATIONS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val NotificationSyncPayload.id: String get() = this.id

    /** [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.notificationsQueries]. */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.notificationsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.notificationsQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.notificationsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.notificationsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.notificationsQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.notificationsQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted rows so
    // the owner receives tombstones.
    override fun readPayload(idStr: String): NotificationSyncPayload? =
        db.notificationsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<NotificationSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.notificationsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: NotificationSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.notificationsQueries.update(
                type = value.type,
                payload = value.body,
                read_at = value.readAt,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.notificationsQueries.insert(
                id = value.id,
                user_id = requireNotNull(userId) { "NotificationRepository.writePayload requires a userId" },
                type = value.type,
                payload = value.body,
                created_at = now,
                updated_at = now,
                read_at = value.readAt,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Marks [notificationId] read for [userId]. Idempotent, and **first read wins**: a second call
     * with a fresher [readAtMs] deliberately does NOT re-stamp the row — cross-device read state
     * converges on the FIRST read, not the latest re-read. A row that does not exist, is
     * tombstoned, or belongs to someone else is NotFound (fail closed — ownership is checked
     * against the stored `user_id`, never trusted from the caller's payload).
     */
    suspend fun markRead(
        notificationId: String,
        userId: String,
        readAtMs: Long,
    ): AppResult<Unit> {
        val row =
            suspendTransaction(db) {
                db.notificationsQueries.selectById(notificationId).executeAsOneOrNull()
            }
        if (row == null || row.user_id != userId || row.deleted_at != null) {
            return AppResult.Failure(
                SyncError.NotFound(domain = domainName, entityId = notificationId),
            )
        }
        if (row.read_at != null) return AppResult.Success(Unit)
        // Not one transaction with the read above — the base's upsert always opens its own
        // suspendTransaction, so the check-then-write window is structural. It is acceptable:
        // ownership was already verified, so the only race is the owner's own row vs their own
        // prune — worst case a just-tombstoned row is resurrected FOR ITS OWNER (writePayload's
        // update branch writes deleted_at = null), never a cross-user effect.
        return when (val result = upsert(row.toSyncPayload().copy(readAt = readAtMs), userId = userId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }
    }

    /**
     * Soft-deletes the oldest live rows beyond [keep] for [userId]. Each prune routes through the
     * substrate's [softDelete], so tombstones bump revisions and reach every device. Returns the
     * number pruned.
     */
    suspend fun pruneToRetention(
        userId: String,
        keep: Int,
    ): Int {
        // Count/select/delete run in separate transactions for the same structural reason as
        // markRead's window: softDelete opens its own — and the race is again owner-only.
        val live = suspendTransaction(db) { db.notificationsQueries.countLiveForUser(userId).executeAsOne() }
        val excess = (live - keep).toInt()
        if (excess <= 0) return 0
        val victims =
            suspendTransaction(db) {
                db.notificationsQueries.selectLiveIdsForUserOldestFirst(userId, excess.toLong()).executeAsList()
            }
        victims.forEach { softDelete(it, userId = userId) }
        return victims.size
    }

    /** Maps a generated [Notifications] row to the wire DTO (drops `user_id` — never on the wire). */
    private fun Notifications.toSyncPayload(): NotificationSyncPayload =
        NotificationSyncPayload(
            id = id,
            type = type,
            body = payload,
            createdAt = created_at,
            updatedAt = updated_at,
            readAt = read_at,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}

/**
 * Hard-deletes every `notifications` and `notification_prefs` row belonging to [userId] — the
 * user-deletion sweep. Call ONLY from inside the deletion's open transaction (the caller's
 * `suspendTransaction` body) so the sweep commits or rolls back atomically with the user row.
 *
 * Deliberately a hard delete that bypasses the substrate's revision/ChangeBus path: both tables
 * are visible only to their owner (`notifications` is userScoped; `notification_prefs` is not
 * syncable at all), and the owner's devices are ejected by the `UserDeleted` control frame — a
 * tombstone would sync to nobody. Living in `server/sync/` keeps the raw write inside the
 * repository layer, per `SyncWritesGoThroughRepositoryRule`.
 */
fun ListenUpDatabase.deleteNotificationRowsForUser(userId: String) {
    notificationsQueries.deleteForUser(userId)
    notificationPrefsQueries.deleteForUser(userId)
}
