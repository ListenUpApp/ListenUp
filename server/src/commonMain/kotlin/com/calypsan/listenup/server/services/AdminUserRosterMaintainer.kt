package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.util.runCatchingCancellable

private val logger = loggerFor<AdminUserRosterMaintainer>()

/**
 * Rebuilds the admin-only `admin_user_roster` projection from the authoritative `users`
 * table. Called whenever a user's admin-visible fields change (creation, approval/denial,
 * role/permission edits, deletion) — one row per ACTIVE or PENDING_APPROVAL user.
 * Idempotent: [refresh] always rewrites the full row from source. Mirrors
 * [PublicProfileMaintainer]'s shape for the admin roster surface instead of the public one.
 */
class AdminUserRosterMaintainer(
    private val sql: ListenUpDatabase,
    private val rosterRepo: AdminUserRosterRepository,
) {
    /**
     * Rebuild and upsert the roster row for [userId] from `users`. No-op if the user row is
     * absent or soft-deleted (e.g. mid-deletion) — the base [selectRosterRowById] query
     * excludes tombstoned rows.
     */
    suspend fun refresh(userId: String) {
        val row =
            suspendTransaction(sql) {
                sql.usersQueries.selectRosterRowById(id = userId).executeAsOneOrNull()
            }
        if (row == null) return

        val payload =
            AdminUserRosterSyncPayload(
                id = userId,
                email = row.email,
                displayName = row.display_name,
                role = row.role,
                status = row.status,
                canShare = row.can_share == 1L,
                accountCreatedAt = row.created_at,
                revision = 0,
                updatedAt = 0,
                createdAt = 0,
                deletedAt = null,
            )
        rosterRepo.upsert(payload, clientOpId = null, userId = null)
    }

    /** Soft-delete the roster row for a user no longer ACTIVE/PENDING_APPROVAL (denied or removed). */
    suspend fun remove(userId: String) {
        rosterRepo.softDelete(userId, clientOpId = null, userId = null)
    }

    /**
     * Best-effort [refresh]: the admin_user_roster projection is a derived view that
     * self-heals via [backfillAll] at startup, so a refresh failure must never fail the
     * user-facing operation that triggered it. Logs and swallows everything except
     * [kotlinx.coroutines.CancellationException].
     */
    suspend fun refreshBestEffort(userId: String) {
        runCatchingCancellable { refresh(userId) }
            .onFailure {
                logger.warn(
                    it,
                ) { "admin_user_roster refresh failed for $userId; projection will self-heal on next backfill" }
            }
    }

    /** Best-effort [remove]; see [refreshBestEffort]. */
    suspend fun removeBestEffort(userId: String) {
        runCatchingCancellable { remove(userId) }
            .onFailure {
                logger.warn(
                    it,
                ) { "admin_user_roster remove failed for $userId; projection will self-heal on next backfill" }
            }
    }

    /**
     * One-time backfill: refresh the projection for every non-deleted ACTIVE or
     * PENDING_APPROVAL user. Idempotent; invoked at startup after migrations to populate the
     * table for pre-existing users.
     */
    suspend fun backfillAll() {
        val userIds =
            suspendTransaction(sql) {
                sql.usersQueries.selectRosterUserIds().executeAsList()
            }
        userIds.forEach { refresh(it) }
    }
}
