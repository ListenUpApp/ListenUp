package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Shelves
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A live shelf paired with its owner's user id.
 *
 * The wire [ShelfSyncPayload] omits `user_id` (it never crosses to other users), so
 * service-layer ownership gating and discovery read the owner through this projection.
 *
 * @property ownerId The user id that owns [shelf].
 * @property shelf The shelf payload (without owner identity).
 */
data class OwnedShelf(
    val ownerId: String,
    val shelf: ShelfSyncPayload,
)

/**
 * SQLDelight syncable repository for per-user, user-owned shelves — the **first
 * user-scoped aggregate** in the Exposed → SQLDelight cutover and the template the
 * playback/listening domains follow.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * routes through the per-user dimension of the [SqlSyncableRepository] base: the
 * owning `user_id` is stamped on insert, and pull/digest filter to the authenticated
 * user via the substrate's `*ForUser` variants
 * ([SyncableSubstrateQueries.selectIdsAboveRevisionForUser] /
 * [SyncableSubstrateQueries.selectIdRevAtMostForUser]).
 *
 * `idAsString(ShelfId) = id.value` is load-bearing — Kotlin's default `toString()`
 * on a value class returns `"ShelfId(value=foo)"`, which would corrupt every column
 * the id is written to.
 *
 * Service-layer helpers beyond the base substrate (each runs in its own transaction):
 *  - [listOwnedBy] — all live shelves for a user
 *  - [findById] — one live shelf by id, or null
 *  - [findOwnedById] — one live shelf + its owner id, or null
 *  - [createStarterShelf] — the one-shot "To Read" shelf created at registration
 *  - [listForOwner] — every live public shelf owned by a user (with owner id)
 *  - [listDiscoverable] — every live public shelf NOT owned by a user (with owner id)
 */
class ShelfRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ShelfSyncPayload, ShelfId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.SHELVES,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override fun idAsString(id: ShelfId): String = id.value

    override val ShelfSyncPayload.id: ShelfId get() = ShelfId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.shelvesQueries].
     *
     * The canonical user-scoped adapter shape: the four global substrate methods forward
     * to the unfiltered queries; the two `*ForUser` methods forward to the user-filtered
     * queries the base calls when [userScoped] is true. Each `*ForUser` query carries the
     * `user_id = :userId` predicate the Exposed base appends in a WHERE clause.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.shelvesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.shelvesQueries
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
                db.shelvesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.shelvesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.shelvesQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.shelvesQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones.
    override fun readPayload(idStr: String): ShelfSyncPayload? =
        db.shelvesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ShelfSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.shelvesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: ShelfSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.shelvesQueries.update(
                name = value.name,
                description = value.description,
                is_private = value.isPrivate.toDbLong(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.shelvesQueries.insert(
                id = value.id,
                user_id = requireNotNull(userId) { "ShelfRepository.writePayload requires a userId" },
                name = value.name,
                description = value.description,
                is_private = value.isPrivate.toDbLong(),
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns all live (non-tombstoned) shelves owned by [userId]. */
    suspend fun listOwnedBy(userId: String): List<ShelfSyncPayload> =
        suspendTransaction(db) {
            db.shelvesQueries
                .listOwnedBy(userId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** Returns the live shelf with [id], or null when absent or tombstoned. */
    suspend fun findById(id: String): ShelfSyncPayload? =
        suspendTransaction(db) {
            db.shelvesQueries
                .selectLiveById(id)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /**
     * Returns the live shelf with [id] alongside its owner's user id, or null when
     * absent or tombstoned. The wire [ShelfSyncPayload] deliberately omits `user_id`,
     * so service-layer ownership gating reads the owner through this projection rather
     * than the payload.
     */
    suspend fun findOwnedById(id: String): OwnedShelf? =
        suspendTransaction(db) {
            db.shelvesQueries
                .selectLiveById(id)
                .executeAsOneOrNull()
                ?.toOwnedShelf()
        }

    /**
     * Creates a "To Read" starter shelf for [userId].
     *
     * This is a one-shot call made at user-registration time. It is intentionally NOT
     * idempotent — registering a user is idempotent at the row level and this call is
     * gated by that; the caller is responsible for calling it only once per user.
     *
     * Returns the created [ShelfSyncPayload] on success, or a [AppResult.Failure] if the
     * underlying [upsert] fails (e.g. a duplicate id collision on the extremely unlikely
     * event of a UUID clash).
     */
    suspend fun createStarterShelf(userId: String): AppResult<ShelfSyncPayload> {
        val now = clock.now().toEpochMilliseconds()
        val payload =
            ShelfSyncPayload(
                id = Uuid.random().toString(),
                name = "To Read",
                description = "",
                isPrivate = false,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        return upsert(payload, userId = userId)
    }

    /**
     * Returns every live, public shelf owned by [ownerId], each paired with its owner's
     * user id — the building block for [getUserShelves][com.calypsan.listenup.server.api.ShelfServiceImpl.getUserShelves].
     * A direct service-layer read that crosses the user-scoping boundary by design.
     */
    suspend fun listForOwner(ownerId: String): List<OwnedShelf> =
        suspendTransaction(db) {
            db.shelvesQueries
                .listPublicForOwner(ownerId)
                .executeAsList()
                .map { it.toOwnedShelf() }
        }

    /**
     * Returns every live, public shelf NOT owned by [excludeUserId], each paired with
     * its owner's user id — the discovery candidate set before per-caller book-access
     * filtering. A direct service-layer read (not a sync-substrate pull): discovery
     * crosses the user-scoping boundary by design.
     */
    suspend fun listDiscoverable(excludeUserId: String): List<OwnedShelf> =
        suspendTransaction(db) {
            db.shelvesQueries
                .listDiscoverable(excludeUserId)
                .executeAsList()
                .map { it.toOwnedShelf() }
        }

    /** Maps a generated [Shelves] row to an [OwnedShelf] (owner id + wire payload). */
    private fun Shelves.toOwnedShelf(): OwnedShelf = OwnedShelf(ownerId = user_id, shelf = toSyncPayload())

    /** Maps a generated [Shelves] row to the wire [ShelfSyncPayload] DTO (drops `user_id`). */
    private fun Shelves.toSyncPayload(): ShelfSyncPayload =
        ShelfSyncPayload(
            id = id,
            name = name,
            description = description,
            // `is_private` is INTEGER (0/1) in SQLite — SQLDelight maps it to Long; convert at
            // the boundary, the same place the Exposed bool adapter sat.
            isPrivate = is_private == 1L,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900

        /** SQLite stores booleans as INTEGER 0/1; map at the write boundary. */
        private fun Boolean.toDbLong(): Long = if (this) 1L else 0L
    }
}
