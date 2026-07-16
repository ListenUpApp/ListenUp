package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.World_events
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

private val log = loggerFor<WorldEventRepository>()

/**
 * SQLDelight syncable repository for the Story World unified event log — library-shared,
 * curated world data, dual-homed under exactly one of a series or a standalone book, and
 * optionally anchored to a book position. Mirrors [EntityRepository]'s access model
 * (`userScoped = false`, curation-gated at the service layer, not per-caller-owned).
 *
 * Unlike [EntityRepository], the single write entry point is [applyBatch]: it accepts the wire
 * [WorldEventOp] list directly (not a pre-built [WorldEventSyncPayload]) because a batch must
 * commit atomically as ONE [suspendTransaction] and recompute each upserted event's mention
 * junction ([world_event_mentions][com.calypsan.listenup.server.db.sqldelight.World_event_mentions])
 * in the same transaction as its root-row write — work that has to happen inside the open
 * transaction, not before it. [WorldEventServiceImpl] validates op shape (exactly-one-home, the
 * per-[WorldEventType] rules) BEFORE ever calling this, so every op [applyBatch] sees is
 * shape-valid; the only failure this repository can still produce is a
 * [WorldEventOp.Delete] targeting a nonexistent event.
 *
 * **Last-write-wins by `updatedAt`**, exactly like [EntityRepository.upsertEntity]: a write
 * strictly older than the stored row's `updatedAt` is a no-op. [WorldEventUpsert] carries no
 * client-supplied `updatedAt` (mirroring [com.calypsan.listenup.api.dto.entity.EntityUpsert]), so
 * this repository stamps one itself per op — see [applyUpsert] — and, per the same rule
 * [EntityRepository.writePayload] documents, [writePayload] persists that stamped
 * [WorldEventSyncPayload.updatedAt] verbatim rather than recomputing a fresh `now()` at write
 * time: the staleness check and the persisted value must be the same reading, or a
 * genuinely-newer write can silently lose to a genuinely-older one that happened to reach the
 * write step later.
 */
class WorldEventRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<WorldEventSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.WORLD_EVENTS,
        clock = clock,
    ) {
    override fun idAsString(id: String): String = id

    override val WorldEventSyncPayload.id: String
        get() = this.id

    override fun WorldEventSyncPayload.revisionOf(): Long = revision

    /**
     * Blanks every content field on a tombstoned event — text, both home fields, the anchor
     * (`bookId`/`positionMs`), subject/object, and mentions — so a catch-up payload never leaks
     * spoiler-relevant content past the tombstone. Mirrors [EntityRepository.minimizeTombstone]'s
     * "no content past the tombstone" contract; the anchor is blanked too (beyond what
     * [EntityRepository] has to consider) because a book position is itself spoiler-relevant.
     * `type`/`source` are left as-is, matching [EntityRepository] leaving `kind` alone — only
     * free-form/identity-adjacent content is blanked, not the typed vocabulary slot.
     */
    override fun minimizeTombstone(payload: WorldEventSyncPayload): WorldEventSyncPayload =
        payload.copy(
            homeSeriesId = null,
            homeBookId = null,
            bookId = null,
            positionMs = null,
            text = "",
            subjectEntityId = null,
            objectEntityId = null,
            mentionIds = emptyList(),
        )

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.worldEventsQueries].
     * Mirrors [EntityRepository.substrate].
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.worldEventsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.worldEventsQueries
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
                db.worldEventsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.worldEventsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones. mentionIds isn't a column on world_events — it is
    // reconstructed from world_event_mentions on every read.
    override fun readPayload(idStr: String): WorldEventSyncPayload? =
        db.worldEventsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload(mentionsFor(listOf(idStr))[idStr] ?: emptyList())

    override fun readPayloads(idStrs: List<String>): List<WorldEventSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.worldEventsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        val mentionsByEvent = mentionsFor(idStrs)
        return idStrs.mapNotNull { id -> byId[id]?.toSyncPayload(mentionsByEvent[id] ?: emptyList()) }
    }

    /**
     * Writes the full aggregate — the `world_events` root row AND the `world_event_mentions`
     * junction, replaced wholesale (delete-then-insert) from [value.mentionIds] — inside the open
     * transaction. The junction MUST be replaced here, not by the caller after the fact: this
     * method's read-back (via [upsertInOpenTransaction]/[readPayload]) is what [applyUpsert]
     * returns and what the live-tail [SyncEvent] carries, so the mentions have to already be
     * correct by the time that read-back runs.
     *
     * Deliberate deviation from the base contract's "updated_at = now", identical to
     * [EntityRepository.writePayload]: `updated_at` is [value]'s own stamped value (see
     * [applyUpsert]), not the transaction's fresh `now` — see the class KDoc for why persisting
     * anything else would break the staleness guard.
     */
    override fun writePayload(
        value: WorldEventSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.worldEventsQueries.update(
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                book_id = value.bookId,
                position_ms = value.positionMs,
                type = value.type.name.lowercase(),
                text = value.text,
                subject_entity_id = value.subjectEntityId,
                object_entity_id = value.objectEntityId,
                source = value.source.name.lowercase(),
                track_id = value.trackId,
                track_version = value.trackVersion,
                revision = rev,
                updated_at = value.updatedAt,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.worldEventsQueries.insert(
                id = value.id,
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                book_id = value.bookId,
                position_ms = value.positionMs,
                type = value.type.name.lowercase(),
                text = value.text,
                subject_entity_id = value.subjectEntityId,
                object_entity_id = value.objectEntityId,
                source = value.source.name.lowercase(),
                track_id = value.trackId,
                track_version = value.trackVersion,
                created_at = now,
                updated_at = value.updatedAt,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
        db.worldEventMentionsQueries.deleteForEvent(value.id)
        value.mentionIds.forEach { entityId ->
            db.worldEventMentionsQueries.insert(event_id = value.id, entity_id = entityId)
        }
    }

    /**
     * Applies every op in [ops] atomically: ONE [suspendTransaction] for the whole batch. Each
     * [WorldEventOp.Upsert] is LWW-guarded and mention-recomputed by [applyUpsert]; each
     * [WorldEventOp.Delete] is tombstoned by [applyDelete]. A [WorldEventOp.Delete] targeting a
     * nonexistent event throws [BatchOpAborted] — the only way to force a rollback here, since
     * [suspendTransaction] commits on any NORMAL return (including a returned
     * [AppResult.Failure]) and rolls back only on a thrown exception — which this method catches
     * just outside the transaction and converts to the typed [AppResult.Failure] the caller sees.
     * Nothing from a failed batch is left applied.
     */
    suspend fun applyBatch(ops: List<WorldEventOp>): AppResult<Unit> {
        // Read once, outside the transaction, and thread into every op — mirrors the bulk-import
        // pattern in PlaybackPositionRepository/ListeningEventRepository.
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        return try {
            suspendTransaction(db) {
                ops.forEach { op ->
                    when (op) {
                        is WorldEventOp.Upsert -> applyUpsert(op.upsert, suppressed)
                        is WorldEventOp.Delete -> applyDelete(op.id, suppressed)
                    }
                }
                AppResult.Success(Unit)
            }
        } catch (e: BatchOpAborted) {
            AppResult.Failure(e.error)
        }
    }

    /**
     * Upserts a single [WorldEventUpsert] inside the caller's open transaction, recomputing the
     * mention set server-side as `extractMentionIds(text) ∪ {subjectEntityId, objectEntityId}` —
     * the client's own notion of mentions (there isn't one; [WorldEventUpsert] carries no
     * `mentionIds` field) is never trusted.
     *
     * [WorldEventUpsert] carries no `updatedAt` (mirroring
     * [com.calypsan.listenup.api.dto.entity.EntityUpsert]), so this stamps one itself
     * ([editTime]) and uses it for BOTH the staleness comparison against the stored row and the
     * persisted value — see the class KDoc for why those two reads must be the same one. A write
     * strictly older than the stored row's `updatedAt` is a no-op, returning the stored payload
     * unchanged (mirrors [EntityRepository.upsertEntity]'s strict-`<` guard: equal is NOT stale).
     */
    private fun TransactionWithReturn<*>.applyUpsert(
        upsert: WorldEventUpsert,
        suppressed: Boolean,
    ): WorldEventSyncPayload {
        val existing = readPayload(upsert.id)
        val editTime = clock.now().toEpochMilliseconds()
        if (existing != null && editTime < existing.updatedAt) {
            return existing
        }
        val mentionIds =
            (MentionTokens.extractMentionIds(upsert.text) + setOfNotNull(upsert.subjectEntityId, upsert.objectEntityId))
                .toList()
        val payload =
            WorldEventSyncPayload(
                id = upsert.id,
                homeSeriesId = upsert.homeSeriesId,
                homeBookId = upsert.homeBookId,
                bookId = upsert.bookId,
                positionMs = upsert.positionMs,
                type = upsert.type,
                text = upsert.text,
                subjectEntityId = upsert.subjectEntityId,
                objectEntityId = upsert.objectEntityId,
                mentionIds = mentionIds,
                source = WorldEventSource.MANUAL,
                trackId = null,
                trackVersion = null,
                revision = existing?.revision ?: 0L,
                updatedAt = editTime,
                createdAt = existing?.createdAt ?: editTime,
                deletedAt = null,
            )
        return upsertInOpenTransaction(payload, suppressed, clientOpId = null, userId = null)
    }

    /**
     * Tombstones the event identified by [id] inside the caller's open transaction and clears its
     * mention junction rows. Throws [BatchOpAborted] (wrapping [SyncError.NotFound]) when no live
     * or tombstoned row exists with [id] — mirroring [SqlSyncableRepository.softDelete]'s
     * not-found failure, translated to a throw here because a normal [AppResult.Failure] return
     * from inside [suspendTransaction] would commit rather than roll back the batch.
     */
    private fun TransactionWithReturn<*>.applyDelete(
        id: String,
        suppressed: Boolean,
    ) {
        val rev = nextRevision()
        val now = clock.now().toEpochMilliseconds()
        val rowsAffected =
            substrate.softDeleteById(id = id, revision = rev, updatedAt = now, deletedAt = now, clientOpId = null)
        if (rowsAffected == 0L) {
            throw BatchOpAborted(SyncError.NotFound(domain = domainName, entityId = id))
        }
        db.worldEventMentionsQueries.deleteForEvent(id)
        if (!suppressed) {
            emitAfterCommit(SyncEvent.Deleted(id = id, revision = rev, occurredAt = now, clientOpId = null))
        } else {
            log.debug { "change suppressed (firehose): domain=$domainName id=$id" }
        }
    }

    /** Reads a live or tombstoned event by id, or null when absent — test/service use. */
    suspend fun findById(idStr: String): WorldEventSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /** Returns every live (non-tombstoned) event anchored to [bookId]. */
    suspend fun listForBook(bookId: String): List<WorldEventSyncPayload> =
        suspendTransaction(db) {
            val rows = db.worldEventsQueries.selectLiveByBook(bookId).executeAsList()
            val mentionsByEvent = mentionsFor(rows.map { it.id })
            rows.map { it.toSyncPayload(mentionsByEvent[it.id] ?: emptyList()) }
        }

    /** Returns every live (non-tombstoned) event that mentions [entityId] (via the junction). */
    suspend fun listForEntity(entityId: String): List<WorldEventSyncPayload> =
        suspendTransaction(db) {
            val rows = db.worldEventsQueries.selectLiveByEntity(entityId).executeAsList()
            val mentionsByEvent = mentionsFor(rows.map { it.id })
            rows.map { it.toSyncPayload(mentionsByEvent[it.id] ?: emptyList()) }
        }

    /**
     * Returns every live (non-tombstoned) event namespaced under exactly one of [homeSeriesId] /
     * [homeBookId] — the caller ([WorldEventServiceImpl]) validates exactly-one-set before this is
     * reached; when neither is set this returns empty rather than guessing.
     */
    suspend fun listForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): List<WorldEventSyncPayload> =
        suspendTransaction(db) {
            val rows =
                when {
                    homeSeriesId != null -> db.worldEventsQueries.selectLiveByHomeSeries(homeSeriesId).executeAsList()
                    homeBookId != null -> db.worldEventsQueries.selectLiveByHomeBook(homeBookId).executeAsList()
                    else -> emptyList()
                }
            val mentionsByEvent = mentionsFor(rows.map { it.id })
            rows.map { it.toSyncPayload(mentionsByEvent[it.id] ?: emptyList()) }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: String): String = idAsString(id)

    /**
     * Batch-hydrates the mention-entity-id list for every event in [eventIds], chunked for
     * SQLite's `IN (…)` variable limit. Used by every read path (single or batch) so a page of N
     * events costs O(chunks), not O(N), extra queries.
     */
    private fun mentionsFor(eventIds: List<String>): Map<String, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        return eventIds
            .chunked(SQLITE_IN_CHUNK)
            .flatMap { chunk -> db.worldEventMentionsQueries.selectMentionsForEvents(chunk).executeAsList() }
            .groupBy({ it.event_id }, { it.entity_id })
    }

    /** Maps a generated [World_events] row to the wire [WorldEventSyncPayload] DTO. */
    private fun World_events.toSyncPayload(mentionIds: List<String>): WorldEventSyncPayload =
        WorldEventSyncPayload(
            id = id,
            homeSeriesId = home_series_id,
            homeBookId = home_book_id,
            bookId = book_id,
            positionMs = position_ms,
            type = WorldEventType.valueOf(type.uppercase()),
            text = text,
            subjectEntityId = subject_entity_id,
            objectEntityId = object_entity_id,
            mentionIds = mentionIds,
            source = WorldEventSource.valueOf(source.uppercase()),
            trackId = track_id,
            trackVersion = track_version,
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

/**
 * Thrown from inside [WorldEventRepository.applyBatch]'s open transaction to force a rollback
 * when an op is invalid at the point it is applied (currently: a [WorldEventOp.Delete] targeting
 * a nonexistent event). [suspendTransaction] commits on any NORMAL return — including a returned
 * [AppResult.Failure] — so aborting the whole batch requires an exception: it unwinds the
 * transaction and is caught by [WorldEventRepository.applyBatch] just outside, converting [error]
 * into the [AppResult.Failure] the caller sees. Never crosses that boundary itself.
 */
private class BatchOpAborted(
    val error: AppError,
) : RuntimeException()
