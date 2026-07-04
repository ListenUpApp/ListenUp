package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase

/**
 * The admin-only `admin_user_roster` domain: a server-maintained materialized view
 * of each user's admin-visible identity (email, role, status, share permission) —
 * server-wins replace, soft-delete tombstones, full digest, [WriteTier.ServerOwned].
 *
 * Delivery is gated to admins **server-side** (firehose and pull filtering);
 * non-admins never receive these events, so the client side is deliberately plain —
 * do not add a client-side gate here.
 */
internal fun adminUserRosterDomain(database: ListenUpDatabase): MirroredDomain<AdminUserRosterSyncPayload> {
    val apply = AdminUserRosterMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.ADMIN_USER_ROSTER,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.adminUserRosterDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.adminUserRosterDao()::digestRows),
        writes = WriteTier.ServerOwned,
    )
}

/** Room mapping for [AdminUserRosterSyncPayload]: unconditional replace. */
internal class AdminUserRosterMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<AdminUserRosterSyncPayload> {
    override suspend fun upsert(payload: AdminUserRosterSyncPayload) {
        database.adminUserRosterDao().upsert(
            AdminUserRosterEntity(
                id = payload.id,
                email = payload.email,
                displayName = payload.displayName,
                role = payload.role,
                status = payload.status,
                canShare = payload.canShare,
                accountCreatedAt = payload.accountCreatedAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.adminUserRosterDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: AdminUserRosterSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
