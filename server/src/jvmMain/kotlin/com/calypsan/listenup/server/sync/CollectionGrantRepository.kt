package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.db.sqldelight.Collection_grants
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock
import kotlinx.serialization.KSerializer

/** Principal type for a user-share grant — the only principal kind today. */
private const val PRINCIPAL_TYPE_USER = "USER"

/**
 * SQLDelight syncable repository for collection grant records — a **global (cross-user)** aggregate.
 *
 * Storage is principal-based (`collection_grants`: `principal_type` / `principal_id` /
 * `granted_by_user_id`), but the wire is unchanged: a USER-principal grant maps to a
 * [CollectionShareSyncPayload] (`sharedWithUserId` / `sharedByUserId`) over sync, and the
 * `domainName` stays `"collection_shares"`. [readPayload] / [writePayload] adapt between the
 * two — `principalType` is always `"USER"` on write. The wire/client rename to "grant" is
 * deferred to the phase that introduces GROUP principals.
 *
 * Permission is stored as a lowercase TEXT column (`"read"` / `"write"`); on read a corrupt or
 * unknown value defaults to [SharePermission.Read] (least-privilege).
 *
 * **Access-filtered sync.** A member's grant catch-up/digest must exclude grants they cannot
 * see (notably their own default ALL_BOOKS grant), so the firehose arrives with a non-null
 * [SqlFragment] `extraWhere` (the `visibleCollectionGrantIdsSql` rule). This class overrides the
 * filtered [pullSince] / [digest] path engine-neutrally over the SQLDelight driver
 * ([selectIdRevAccessFiltered]); the unfiltered (admin / null) path delegates to the base —
 * identical to [com.calypsan.listenup.server.services.BookRepository] / [CollectionRepository].
 *
 * Service-layer helpers beyond the base substrate (all USER-principal scoped):
 *  - [findActiveGrant] — the live grant for a `(collectionId, userId)` pair
 *  - [listActiveGrantsForCollection] — all live USER grants on a collection
 *  - [listActiveGrantsForUser] — all collections granted to a user
 *  - [softDeleteGrant] — revoke the active grant for a `(collectionId, userId)` pair
 */
class CollectionGrantRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    private val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<CollectionShareSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        // Wire domain stays "collection_shares" — see class KDoc. A USER-principal grant is a
        // share on the wire; the wire/client rename is deferred to when GROUP principals exist.
        domainName = "collection_shares",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<CollectionShareSyncPayload> = CollectionShareSyncPayload.serializer()

    override val CollectionShareSyncPayload.id: String get() = this.id

    override fun CollectionShareSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.collectionGrantsQueries].
     * Global aggregate — the `*ForUser` variants stay the base's throwing defaults.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.collectionGrantsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.collectionGrantsQueries
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
                db.collectionGrantsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.collectionGrantsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted rows so
    // clients receive tombstones.
    override fun readPayload(idStr: String): CollectionShareSyncPayload? =
        db.collectionGrantsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSharePayload()

    override fun readPayloads(idStrs: List<String>): List<CollectionShareSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.collectionGrantsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSharePayload() }
    }

    override fun writePayload(
        value: CollectionShareSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // A USER-principal grant is the only kind today; map the share payload onto the
        // principal columns (principalType = "USER").
        if (existed) {
            db.collectionGrantsQueries.update(
                collection_id = value.collectionId,
                principal_type = PRINCIPAL_TYPE_USER,
                principal_id = value.sharedWithUserId,
                granted_by_user_id = value.sharedByUserId,
                permission = value.permission.name.lowercase(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.collectionGrantsQueries.insert(
                id = value.id,
                collection_id = value.collectionId,
                principal_type = PRINCIPAL_TYPE_USER,
                principal_id = value.sharedWithUserId,
                granted_by_user_id = value.sharedByUserId,
                permission = value.permission.name.lowercase(),
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Returns the active (non-tombstoned) USER grant for `(collectionId, userId)`, or null when
     * no live grant exists.
     */
    suspend fun findActiveGrant(
        collectionId: String,
        userId: String,
    ): CollectionShareSyncPayload? =
        suspendTransaction(db) {
            db.collectionGrantsQueries
                .selectActiveUserGrant(collectionId, userId)
                .executeAsOneOrNull()
                ?.toSharePayload()
        }

    /** Returns all active (non-tombstoned) USER grants on [collectionId]. */
    suspend fun listActiveGrantsForCollection(collectionId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            db.collectionGrantsQueries
                .listActiveUserGrantsForCollection(collectionId)
                .executeAsList()
                .map { it.toSharePayload() }
        }

    /** Returns all active (non-tombstoned) USER grants where [userId] is the recipient. */
    suspend fun listActiveGrantsForUser(userId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            db.collectionGrantsQueries
                .listActiveUserGrantsForPrincipal(userId)
                .executeAsList()
                .map { it.toSharePayload() }
        }

    /**
     * Soft-deletes the active USER grant for `(collectionId, userId)`. Bumps revision and
     * publishes [com.calypsan.listenup.api.sync.SyncEvent.Deleted]. Returns [AppResult.Failure]
     * if no live grant exists for this pair.
     */
    suspend fun softDeleteGrant(
        collectionId: String,
        userId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> {
        val grant =
            findActiveGrant(collectionId, userId) ?: return AppResult.Failure(
                SyncError.NotFound(
                    domain = domainName,
                    entityId = "$collectionId:$userId",
                ),
            )
        return softDelete(grant.id, clientOpId = clientOpId)
    }

    /**
     * Access-filtered catch-up pull. The unfiltered path ([extraWhere] null — admins) delegates
     * to the base; the filtered path (a member's `visibleCollectionGrantIdsSql` subquery) reads
     * the `(id, revision)` page engine-neutrally over the SQLDelight driver and hydrates via the
     * SQLDelight [readPayloads].
     *
     * Note the access fragment selects `g.id` from `collection_grants` and the splice runs against
     * the same `collection_grants` table — the wire `domainName` is `collection_shares`, but the
     * stored table is `collection_grants`.
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<CollectionShareSyncPayload> {
        if (extraWhere == null) return super.pullSince(userId, cursor, limit, extraWhere)
        return suspendTransaction(db) {
            val idsWithRev =
                driver.selectIdRevAccessFiltered(
                    table = "collection_grants",
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
     * Access-filtered drift digest. The unfiltered path delegates to the base; the filtered path
     * reads the `(id, revision)` slice engine-neutrally over the SQLDelight driver and computes
     * the permanent-wire-contract SHA-256 digest identically to the base.
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
                    table = "collection_grants",
                    revisionPredicate = "revision <= ?",
                    revisionArg = cursor,
                    extraWhere = extraWhere,
                    ascendingByRevision = false,
                    limit = null,
                )
            }.sortedBy { it.id }
        return accessFilteredDigest(cursor, rows)
    }

    /**
     * Adapts a generated [Collection_grants] row to the unchanged wire [CollectionShareSyncPayload]:
     * `principalId` → `sharedWithUserId`, `granted_by_user_id` → `sharedByUserId`.
     */
    private fun Collection_grants.toSharePayload(): CollectionShareSyncPayload =
        CollectionShareSyncPayload(
            id = id,
            collectionId = collection_id,
            sharedWithUserId = principal_id,
            sharedByUserId = granted_by_user_id,
            permission = permission.toSharePermission(),
            revision = revision,
            updatedAt = updated_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /** Chunk size for `IN (…)` batch reads. Kept under SQLite's default 999 var limit. */
        const val SQLITE_IN_CHUNK = 900
    }
}

/**
 * Converts a stored TEXT permission value to [SharePermission]. A corrupt or unrecognised value
 * defaults to [SharePermission.Read] — the least-privilege choice — rather than throwing.
 */
private fun String.toSharePermission(): SharePermission =
    if (this == "write") SharePermission.Write else SharePermission.Read
