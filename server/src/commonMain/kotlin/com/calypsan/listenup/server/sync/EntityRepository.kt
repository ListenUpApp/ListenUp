package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.Entities
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for Story World entities (characters, locations,
 * items) — library-shared, curated world data, dual-homed under exactly one of a
 * series or a standalone book.
 *
 * Unlike [ReadingOrderRepository], this is NOT userScoped: entities are curated
 * content behind the metadata-edit gate ([com.calypsan.listenup.server.auth.UserPermissionPolicy.requireCanEdit]),
 * writable by anyone holding that permission — the same access model as
 * [com.calypsan.listenup.server.services.SeriesRepository] / [com.calypsan.listenup.server.services.GenreRepository],
 * not the per-user reading-order model.
 *
 * `idAsString` is explicitly overridden as the identity function on a plain
 * [String] id — matching the newer `String`-id domains
 * ([com.calypsan.listenup.server.services.ActivitySyncRepository]) rather than a
 * value-class id like [com.calypsan.listenup.core.ReadingOrderId], and matching
 * the sibling domains' convention of overriding explicitly rather than relying on
 * the base's `toString()` default.
 *
 * **Last-write-wins by `updatedAt`.** The [SqlSyncableRepository] base's `upsert`
 * has no staleness check of its own (it always writes and bumps the domain
 * revision) — [upsertEntity] adds the guard: a write strictly OLDER than the
 * stored row's `updatedAt` is a no-op that still returns [AppResult.Success] with
 * the stored payload unchanged. For that comparison to be meaningful,
 * [writePayload] persists the WRITER-SUPPLIED `updatedAt` into the `updated_at`
 * column instead of the base contract's server-clock `now` — this domain has no
 * separate LWW column the way playback positions have `last_played_at`, so the
 * substrate column doubles as the LWW timestamp (see the comment in
 * [writePayload]). See [upsertEntity]'s KDoc for why the guard uses a strict `<`
 * rather than
 * [PlaybackPositionRepository.recordPosition][com.calypsan.listenup.server.services.PlaybackPositionRepository.recordPosition]'s
 * `>=` guard.
 */
class EntityRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<EntitySyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.ENTITIES,
        clock = clock,
    ) {
    override fun idAsString(id: String): String = id

    override val EntitySyncPayload.id: String
        get() = this.id

    override fun EntitySyncPayload.revisionOf(): Long = revision

    /**
     * Entities are not access-filtered ([driver] stays null, matching the tag/genre/series
     * curation domains), so the base's identity default would be sufficient for the access
     * model alone. Overridden anyway: a tombstoned entity's name/home must never linger in a
     * catch-up payload once deleted — the same "no content past the tombstone" contract
     * [CollectionRepository][com.calypsan.listenup.server.sync.CollectionRepository] applies for
     * privacy reasons, applied here for spoiler-safety reasons instead.
     */
    override fun minimizeTombstone(payload: EntitySyncPayload): EntitySyncPayload =
        payload.copy(name = "", homeSeriesId = null, homeBookId = null, imageRef = null)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.entitiesQueries].
     * Mirrors the canonical global-domain shape (see [com.calypsan.listenup.server.services.SeriesRepository]).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.entitiesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.entitiesQueries
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
                db.entitiesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.entitiesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones.
    override fun readPayload(idStr: String): EntitySyncPayload? =
        db.entitiesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<EntitySyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.entitiesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: EntitySyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // Deliberate deviation from the base contract's "updated_at = now": this domain's
        // `updated_at` IS the LWW timestamp [upsertEntity] compares against. PlaybackPosition
        // keeps a separate domain column (`last_played_at`) as its LWW comparand and lets the
        // substrate stamp `updated_at`; entities have no second column — the wire payload's
        // `updatedAt` is the LWW axis — so the writer-supplied value must be what's stored, or
        // the guard would compare incoming edit-times against the server's wall clock and
        // reject every re-edit as stale. Nothing in the base machinery reads `updated_at`
        // (pull cursors and digests key on `revision`; tombstones on `deleted_at`).
        if (existed) {
            db.entitiesQueries.update(
                kind = value.kind.name.lowercase(),
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                name = value.name,
                image_ref = value.imageRef,
                revision = rev,
                updated_at = value.updatedAt,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.entitiesQueries.insert(
                id = value.id,
                kind = value.kind.name.lowercase(),
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                name = value.name,
                image_ref = value.imageRef,
                created_at = now,
                updated_at = value.updatedAt,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Upserts [value] with an `updatedAt`-wins staleness guard: a write strictly OLDER than the
     * currently stored row's `updatedAt` is a no-op, returning the stored payload unchanged — a
     * stale offline write never clobbers a fresher edit from another device or caller. An
     * incoming `updatedAt` equal to the stored value is NOT treated as stale (it still writes) —
     * unlike [PlaybackPositionRepository.recordPosition][com.calypsan.listenup.server.services.PlaybackPositionRepository.recordPosition]'s
     * `>=` guard, which is safe only because a repeated `lastPlayedAt` there always means a
     * literal retry of the same physical position report. Entities have no such guarantee (two
     * genuinely distinct edits can land in the same server-clock millisecond), so only a
     * strictly-older write short-circuits. Re-applying the IDENTICAL upsert twice still converges
     * to the same stored content either way — the second apply just also writes (bumping
     * revision) rather than short-circuiting.
     */
    suspend fun upsertEntity(value: EntitySyncPayload): AppResult<EntitySyncPayload> {
        val existing = findById(value.id)
        if (existing != null && value.updatedAt < existing.updatedAt) {
            return AppResult.Success(existing)
        }
        return upsert(value, clientOpId = null)
    }

    /** Reads a live or tombstoned entity by id, or null when absent — test/service use. */
    suspend fun findById(idStr: String): EntitySyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /** Returns every live (non-tombstoned) entity namespaced under [seriesId]. */
    suspend fun listBySeries(seriesId: String): List<EntitySyncPayload> =
        suspendTransaction(db) {
            db.entitiesQueries
                .selectLiveBySeries(seriesId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** Returns every live (non-tombstoned) entity namespaced under standalone [bookId]. */
    suspend fun listByBook(bookId: String): List<EntitySyncPayload> =
        suspendTransaction(db) {
            db.entitiesQueries
                .selectLiveByBook(bookId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: String): String = idAsString(id)

    /** Maps a generated [Entities] row to the wire [EntitySyncPayload] DTO. */
    private fun Entities.toSyncPayload(): EntitySyncPayload =
        EntitySyncPayload(
            id = id,
            kind = EntityKind.valueOf(kind.uppercase()),
            name = name,
            homeSeriesId = home_series_id,
            homeBookId = home_book_id,
            imageRef = image_ref,
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
    }
}
