package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository

/**
 * Decides collection-level access for a `(user, role, collection)` triple.
 *
 * Reused by `CollectionService` to gate mutations and scope listing. The decision
 * resolves in a fixed precedence order: a tombstoned (or absent) collection denies;
 * the owner gets [SharePermission.Write] with `isOwner = true`; an admin/root bypasses
 * to [SharePermission.Write] (but `isOwner = false` unless they also own it — the owner
 * check runs first to keep `isOwner` accurate); otherwise the active share's permission
 * applies; with no relationship, access is denied.
 *
 * Book-level visibility (which books in a collection a user may see) is a separate
 * concern handled in Collections-1b.
 */
internal class CollectionAccessPolicy(
    private val collectionRepo: CollectionRepository,
    private val shareRepo: CollectionShareRepository,
) {
    /**
     * The access verdict for a collection: whether the caller may touch it
     * ([canAccess]), the [permission] they hold, and whether they [isOwner].
     */
    data class Decision(
        val canAccess: Boolean,
        val permission: SharePermission,
        val isOwner: Boolean,
    )

    /**
     * Resolves the [Decision] for [userId] (with server [role]) against [collectionId].
     *
     * Order: deleted/missing → owner → admin → active share → deny.
     */
    suspend fun decide(
        userId: String,
        role: UserRoleColumn,
        collectionId: String,
    ): Decision {
        val coll =
            collectionRepo.findById(collectionId)
                ?: return Decision(false, SharePermission.Read, false)
        if (coll.deletedAt != null) return Decision(false, SharePermission.Read, false)
        if (coll.ownerId == userId) return Decision(true, SharePermission.Write, true)
        if (role == UserRoleColumn.ROOT || role == UserRoleColumn.ADMIN) {
            return Decision(true, SharePermission.Write, false)
        }
        val share =
            shareRepo.findActiveShare(collectionId, userId)
                ?: return Decision(false, SharePermission.Read, false)
        return Decision(true, share.permission, false)
    }

    /** True when [userId] may read [collectionId]. */
    suspend fun canRead(
        userId: String,
        role: UserRoleColumn,
        collectionId: String,
    ): Boolean = decide(userId, role, collectionId).canAccess

    /**
     * True when [userId] may write to [collectionId]. Completes the read/write convenience
     * pair; staged for Collections-1b book-write gating — intentionally uncalled in 1a (the
     * service gates writes via [decide]'s full [Decision] to split Forbidden vs NotFound).
     */
    suspend fun canWrite(
        userId: String,
        role: UserRoleColumn,
        collectionId: String,
    ): Boolean = decide(userId, role, collectionId).let { it.canAccess && it.permission.canWrite() }
}
