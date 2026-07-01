package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.db.sqldelight.Admin_user_roster
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock
import kotlinx.serialization.KSerializer

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
 * [SqlFragment] `extraWhere` (the `WHERE 1 = 0` hidden subquery) that the base
 * [SqlSyncableRepository] cannot splice. This class [overrides][pullSince] the filtered path
 * the same way [com.calypsan.listenup.server.services.LibraryFolderRepository] does — splicing
 * `id IN (<extraWhere.sql>)` engine-neutrally over the shared SQLDelight [SqlDriver]
 * ([selectIdRevAccessFiltered]) and hydrating via the base's [readPayloads]. The unfiltered
 * (admin / null) path delegates to the base.
 */
class AdminUserRosterRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    private val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<AdminUserRosterSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        domainName = "admin_user_roster",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<AdminUserRosterSyncPayload> =
        AdminUserRosterSyncPayload.serializer()

    override val AdminUserRosterSyncPayload.id: String get() = this.id

    override fun AdminUserRosterSyncPayload.revisionOf(): Long = revision

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

    /**
     * Access-filtered catch-up pull. The unfiltered path ([extraWhere] null — admins, who
     * see every roster row) delegates to the base; the filtered path (a non-admin's
     * `WHERE 1 = 0` hidden subquery) reads the `(id, revision)` page engine-neutrally over the
     * SQLDelight driver and hydrates via the base's [readPayloads]. Mirrors
     * [com.calypsan.listenup.server.services.LibraryFolderRepository.pullSince].
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<AdminUserRosterSyncPayload> {
        if (extraWhere == null) return super.pullSince(userId, cursor, limit, extraWhere)
        return suspendTransaction(db) {
            val idsWithRev =
                driver.selectIdRevAccessFiltered(
                    table = domainName,
                    revisionPredicate = "revision > ?",
                    revisionArg = cursor,
                    extraWhere = extraWhere,
                    ascendingByRevision = true,
                    limit = limit,
                )
            Page(
                items = readPayloads(idsWithRev.map { it.id }),
                nextCursor = idsWithRev.lastOrNull()?.revision,
                hasMore = idsWithRev.size == limit,
            )
        }
    }

    /**
     * Access-filtered drift digest. The unfiltered path delegates to the base; the filtered
     * path reads the `(id, revision)` slice engine-neutrally over the SQLDelight driver and
     * computes the permanent-wire-contract SHA-256 digest identically to the base. Mirrors
     * [com.calypsan.listenup.server.services.LibraryFolderRepository.digest].
     */
    override suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment?,
    ): DomainDigest {
        if (extraWhere == null) return super.digest(userId, cursor, extraWhere)
        val rows =
            suspendTransaction(db) {
                driver.selectIdRevAccessFiltered(
                    table = domainName,
                    revisionPredicate = "revision <= ?",
                    revisionArg = cursor,
                    extraWhere = extraWhere,
                    ascendingByRevision = false,
                    limit = null,
                )
            }.sortedBy { it.id }
        return accessFilteredDigest(cursor, rows)
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
