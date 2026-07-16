package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.Admin_user_roster
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for the admin-only `admin_user_roster` projection.
 *
 * Global (`userScoped` defaults `false`) — the substrate itself is unfiltered; admin-only
 * delivery is enforced on the firehose (see SyncRoutes), not by a per-user column here.
 * Single-table; the maintainer assembles the full payload, so [writePayload] is a straight
 * INSERT/UPDATE of all columns — the [PublicProfileRepository] pattern.
 *
 * `can_share` is `INTEGER` (0/1) in SQLite, which SQLDelight surfaces as `Long`
 * (see [Admin_user_roster.can_share]); [writePayload] / [toSyncPayload] convert at the boundary.
 *
 * `id` is a plain `String` (`id == userId`), so the default `idAsString` is correct.
 *
 * **Access-filtered sync.** A row carries a user's email/role/status, so the firehose gates
 * the whole domain admin-only: a non-admin catch-up/digest arrives with a non-null
 * [SqlFragment] `extraWhere` (the `WHERE 1 = 0` hidden subquery). The base
 * [SqlSyncableRepository] splices it engine-neutrally over the injected [SqlDriver]; this class
 * only wires that driver. The unfiltered (admin / null) path takes the base's substrate read
 * unchanged.
 */
class AdminUserRosterRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<AdminUserRosterSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.ADMIN_USER_ROSTER,
        clock = clock,
    ) {
    override val AdminUserRosterSyncPayload.id: String get() = this.id

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.adminUserRosterQueries].
     * Global aggregate — only the four unfiltered substrate methods are wired; the `*ForUser`
     * variants keep the throwing defaults (never called for a non-userScoped domain).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.adminUserRosterQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.adminUserRosterQueries
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
                db.adminUserRosterQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.adminUserRosterQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): AdminUserRosterSyncPayload? =
        db.adminUserRosterQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    /** Tombstone projection — see [SqlSyncableRepository.minimizeTombstone]. */
    override fun minimizeTombstone(payload: AdminUserRosterSyncPayload): AdminUserRosterSyncPayload =
        payload.copy(
            email = "",
            displayName = "",
            role = "",
            status = "",
            canShare = false,
            accountCreatedAt = 0L,
        )

    override fun writePayload(
        value: AdminUserRosterSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.adminUserRosterQueries.update(
                email = value.email,
                display_name = value.displayName,
                role = value.role,
                status = value.status,
                can_share = value.canShare.toDbLong(),
                account_created_at = value.accountCreatedAt,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.adminUserRosterQueries.insert(
                id = value.id,
                email = value.email,
                display_name = value.displayName,
                role = value.role,
                status = value.status,
                can_share = value.canShare.toDbLong(),
                account_created_at = value.accountCreatedAt,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Maps a generated [Admin_user_roster] row to the wire [AdminUserRosterSyncPayload] DTO. */
    private fun Admin_user_roster.toSyncPayload(): AdminUserRosterSyncPayload =
        AdminUserRosterSyncPayload(
            id = id,
            email = email,
            displayName = display_name,
            role = role,
            status = status,
            canShare = can_share == 1L,
            accountCreatedAt = account_created_at,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private fun Boolean.toDbLong(): Long = if (this) 1L else 0L
}
