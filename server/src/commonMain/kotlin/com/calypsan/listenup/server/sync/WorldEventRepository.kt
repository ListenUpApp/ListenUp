package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.WorldEventRules
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.World_events
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * The `world_events` syncable repository: Story World events — the lines of a world's log — homed on a series
 * or a book like entities, and optionally anchored to a moment in one of that world's books.
 *
 * **One transaction per batch.** [applyBatch] takes its first revision before it reads anything, which makes
 * the transaction the writer, then for each op reads the current row, decides, writes and records a
 * [WorldEventHistory] row. A refused op is a `rollback`: the rows, the history, the revision and the reserved
 * firehose slots of the whole batch go with it, so a batch lands whole or not at all.
 *
 * **Server-derived mentions.** [writePayload] recomputes `world_event_mentions` on every write path, keeping
 * only entities of the event's own world ([WorldEventIntegrity.mentionIds]), so a mention never names an
 * entity a viewer of the event can't see. A tombstone keeps none.
 *
 * **Arrival order wins**, as for every syncable domain: a write is a full overwrite and the base stamps
 * `updated_at` with the server's clock.
 *
 * **Access-gated** by home and anchor: [driver] carries the caller's visibility subquery into the filtered pull.
 */
class WorldEventRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<WorldEventSyncPayload, WorldEventId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.WORLD_EVENTS,
        clock = clock,
    ) {
    private val history = WorldEventHistory(db)
    private val integrity = WorldEventIntegrity(db)

    override val WorldEventSyncPayload.id: WorldEventId
        get() = WorldEventId(id)

    override fun idAsString(id: WorldEventId): String = id.value

    /** A tombstone skips the access gate, so it carries identity only — not even its type, home or anchor. */
    override fun minimizeTombstone(payload: WorldEventSyncPayload): WorldEventSyncPayload =
        payload.copy(
            homeSeriesId = null,
            homeBookId = null,
            bookId = null,
            positionMs = null,
            type = WorldEventType.UNKNOWN,
            text = "",
            detail = null,
            subjectEntityId = null,
            objectEntityId = null,
            mentionIds = emptyList(),
            createdBy = null,
            updatedBy = null,
        )

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

    override fun readPayload(idStr: String): WorldEventSyncPayload? =
        db.worldEventsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.let { hydrate(listOf(it)).single() }

    override fun readPayloads(idStrs: List<String>): List<WorldEventSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            hydrate(
                idStrs
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> db.worldEventsQueries.selectByIds(chunk).executeAsList() },
            ).associateBy { it.id }
        return idStrs.mapNotNull { byId[it] }
    }

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
                type = value.type.storageName(),
                text = value.text,
                detail = value.detail,
                subject_entity_id = value.subjectEntityId,
                object_entity_id = value.objectEntityId,
                updated_by = value.updatedBy,
                updated_at = now,
                revision = rev,
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
                type = value.type.storageName(),
                text = value.text,
                detail = value.detail,
                subject_entity_id = value.subjectEntityId,
                object_entity_id = value.objectEntityId,
                created_by = value.createdBy,
                updated_by = value.updatedBy,
                created_at = now,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
            )
        }
        db.worldEventMentionsQueries.deleteForEvent(value.id)
        integrity.mentionIds(value).forEach { entityId ->
            db.worldEventMentionsQueries.insert(event_id = value.id, entity_id = entityId)
        }
    }

    // ── Reads ──

    /** The event with [id], live or tombstoned, or null. */
    suspend fun findById(id: WorldEventId): WorldEventSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /** Live events homed on [seriesId], oldest first. */
    suspend fun listLiveForSeries(seriesId: SeriesId): List<WorldEventSyncPayload> =
        suspendTransaction(db) { hydrate(db.worldEventsQueries.selectLiveBySeries(seriesId.value).executeAsList()) }

    /** Live events homed on [bookId], oldest first. */
    suspend fun listLiveForBook(bookId: BookId): List<WorldEventSyncPayload> =
        suspendTransaction(db) { hydrate(db.worldEventsQueries.selectLiveByBook(bookId.value).executeAsList()) }

    /** Live events that mention [entityId] — as subject, object or a text token — oldest first. */
    suspend fun listLiveMentioning(entityId: EntityId): List<WorldEventSyncPayload> =
        suspendTransaction(db) { hydrate(db.worldEventsQueries.selectLiveMentioning(entityId.value).executeAsList()) }

    /** [id]'s history, newest first. */
    suspend fun listHistory(id: WorldEventId): List<WorldEventChange> = suspendTransaction(db) { history.listFor(id) }

    /** One history entry of an event, or null. */
    suspend fun findChange(changeId: StoryWorldHistoryId): WorldEventChange? =
        suspendTransaction(db) { history.find(changeId) }

    // ── Writes ──

    /**
     * Applies [ops] in order, in **one** transaction, as [actor]. Each upsert is a full overwrite that records
     * CREATE or UPDATE; each delete tombstones a live event and records DELETE. The rules are decided against
     * the stored row inside the transaction, never from an earlier read:
     * - the home is fixed at creation; an edit naming another home is a [ValidationError];
     * - [WorldEventType.UNKNOWN] is refused on create and keeps the stored type on an edit;
     * - [WorldEventRules.contentProblem] for the type being written;
     * - a deleted event stays deleted: an edit or delete of it is [WorldEventError.NotFound] (only [revert] or a
     *   book re-add revives);
     * - the anchor is a live book of the world; subject and object are live entities of the world of allowed kinds;
     * - authorship is the server's (`createdBy` on create, `updatedBy` every time), whatever the payload claims.
     *
     * Any refusal rolls the whole batch back and returns it.
     */
    suspend fun applyBatch(
        ops: List<WorldEventOp>,
        actor: UserId?,
    ): AppResult<Unit> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            for (op in ops) {
                val refusal =
                    when (op) {
                        is WorldEventOp.Upsert -> applyUpsert(op.upsert, actor, ctx, lease.take())
                        is WorldEventOp.Delete -> applyDelete(op.id, actor, ctx, lease.take())
                    }
                if (refusal != null) rollback(AppResult.Failure(refusal))
            }
            AppResult.Success(Unit)
        }
    }

    /**
     * Restores the `before` of [changeId] as a new forward write and records REVERT: a CREATE reverts to a
     * tombstone; anything else restores (and revives) the earlier snapshot in the event's current home.
     *
     * The change, the current row and the restore are read inside the write's transaction, so the REVERT's
     * `before` is the row as it stands. The restored anchor must still be a live book of the world, and its
     * participants entities of the world — deleted ones allowed, so a later delete never blocks an undo. Any
     * refusal rolls back.
     */
    suspend fun revert(
        changeId: StoryWorldHistoryId,
        actor: UserId?,
    ): AppResult<WorldEventChange> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val rev = nextRevision()
            val change =
                history.find(changeId)
                    ?: rollback(
                        AppResult.Failure(WorldEventError.HistoryNotFound(debugInfo = "change=${changeId.value}")),
                    )
            val current =
                readPayload(change.eventId.value)
                    ?: rollback(
                        AppResult.Failure(WorldEventError.NotFound(debugInfo = "event=${change.eventId.value}")),
                    )
            val restore =
                change.before
                    ?: return@suspendTransaction AppResult.Success(
                        tombstone(before = current, op = StoryWorldOp.REVERT, actor = actor, ctx = ctx, revision = rev),
                    )
            val home = WorldHome(current.homeSeriesId, current.homeBookId)
            val problem =
                integrity.anchorProblem(restore.bookId, home)
                    ?: integrity.participantProblem(
                        restore.type,
                        restore.subjectEntityId,
                        restore.objectEntityId,
                        home,
                        requireLive = false,
                    )
            if (problem != null) rollback(AppResult.Failure(problem))
            val restored =
                restore.copy(
                    homeSeriesId = current.homeSeriesId,
                    homeBookId = current.homeBookId,
                    revision = current.revision,
                    updatedBy = actor?.value,
                    deletedAt = null,
                )
            AppResult.Success(
                rewrite(
                    before = current,
                    after = restored,
                    op = StoryWorldOp.REVERT,
                    actor = actor,
                    ctx = ctx,
                    revision = rev,
                ),
            )
        }
    }

    // ── Book removal cascade (BookRepository.softDelete / reviveByIds) ──
    // Like every write here, each takes its revision before it reads, so the transaction is the writer first.
    // A cheap read-only check runs before that, so a book no event touches burns no revision.

    /** Tombstones every live event homed on or anchored to [bookId] (DELETE, no actor). Returns how many. */
    suspend fun softDeleteAllForBook(bookId: String): Int {
        if (!suspendTransaction(
                db,
            ) { db.worldEventsQueries.hasLiveTouchingBook(bookId, bookId).executeAsOne() }
        ) {
            return 0
        }
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            val live = db.worldEventsQueries.selectLiveIdsTouchingBook(bookId, bookId).executeAsList()
            live.forEach { id ->
                tombstone(
                    before = checkNotNull(readPayload(id)),
                    op = StoryWorldOp.DELETE,
                    actor = null,
                    ctx = ctx,
                    revision = lease.take(),
                )
            }
            live.size
        }
    }

    /**
     * Revives the events of [bookIds] that a book removal tombstoned (REVERT, no actor): exactly those whose
     * newest history row is the cascade's actorless DELETE, so an event someone deleted by hand stays deleted.
     */
    suspend fun reviveAllForBooks(bookIds: List<String>): Int {
        if (bookIds.isEmpty()) return 0
        if (suspendTransaction(db) { cascadeDeletedFor(bookIds) }.isEmpty()) return 0
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            val dead = cascadeDeletedFor(bookIds)
            dead.forEach { id ->
                val before = checkNotNull(readPayload(id))
                rewrite(
                    before = before,
                    after = before.copy(deletedAt = null),
                    op = StoryWorldOp.REVERT,
                    actor = null,
                    ctx = ctx,
                    revision = lease.take(),
                )
            }
            dead.size
        }
    }

    private fun cascadeDeletedFor(bookIds: List<String>): List<String> =
        bookIds.chunked(SQLITE_IN_CHUNK / 2).flatMap { chunk ->
            db.worldEventsQueries.selectCascadeDeletedForBooks(chunk, chunk).executeAsList()
        }

    // ── Series merge (SeriesServiceImpl.mergeSeries / SeriesMergeReceipts.undo) ──

    /**
     * Records [source]'s live events against [receiptId], then re-homes each to [target] (UPDATE, no actor: a
     * cascade of the series merge). Anchors stay — the books moved with the merge. Call after the entities
     * re-home, so mentions recompute against entities already in [target]. Returns how many moved.
     */
    suspend fun rehomeForSeriesMerge(
        receiptId: String,
        source: SeriesId,
        target: SeriesId,
    ): Int {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            db.seriesMergeReceiptsQueries.snapshotSourceWorldEvents(receipt_id = receiptId, source_id = source.value)
            val moving = db.seriesMergeReceiptsQueries.selectReceiptWorldEventIds(receiptId).executeAsList()
            moving.forEach { id ->
                val before = checkNotNull(readPayload(id))
                rewrite(
                    before = before,
                    after = before.copy(homeSeriesId = target.value),
                    op = StoryWorldOp.UPDATE,
                    actor = null,
                    ctx = ctx,
                    revision = lease.take(),
                )
            }
            moving.size
        }
    }

    /**
     * The Story World events half of a series-merge undo, bound to this call's firehose context.
     * [SeriesMergeUndo.restore] runs **inside** the undo's claim transaction, so the receipt is never marked
     * undone without its events going home.
     */
    suspend fun prepareSeriesMergeUndo(): SeriesMergeUndo = SeriesMergeUndo(writeContext())

    /** See [prepareSeriesMergeUndo]. */
    inner class SeriesMergeUndo internal constructor(
        private val ctx: WriteContext,
    ) {
        /**
         * Moves [receiptId]'s events that are still live under [target] back to [source] (UPDATE, no actor), each in
         * its current state. An event created under [target] since the merge is not on the receipt, so it stays.
         * Mentions recompute against [source]'s world on the way back. Call inside [transaction], after the
         * entities' restore. Returns how many moved back.
         */
        fun restore(
            transaction: TransactionWithReturn<*>,
            receiptId: String,
            source: SeriesId,
            target: SeriesId,
        ): Int =
            with(transaction) {
                val lease = RevisionLease(nextRevision())
                val returning =
                    db.seriesMergeReceiptsQueries
                        .selectReceiptWorldEventIds(receiptId)
                        .executeAsList()
                        .mapNotNull { id -> liveOrNull(id)?.takeIf { it.homeSeriesId == target.value } }
                returning.forEach { before ->
                    rewrite(
                        before = before,
                        after = before.copy(homeSeriesId = source.value),
                        op = StoryWorldOp.UPDATE,
                        actor = null,
                        ctx = ctx,
                        revision = lease.take(),
                    )
                }
                returning.size
            }
    }

    // ── Targeted pull ──

    /**
     * The scoped `AccessChanged` delta asks this domain by **book id** (`TargetedMatch.BOOK_ID`). An event is
     * touched by a book it is homed on, a book it is anchored to, or any book of the series it is homed on, so
     * this resolves the asked-about books to every event they touch (tombstones included), then lets the base
     * answer by event id — still access-filtered by [extraWhere], so a hidden event never comes back and the
     * client tombstones it.
     */
    override suspend fun pullByIds(
        userId: String?,
        matchColumn: String,
        matchValues: List<String>,
        extraWhere: SqlFragment?,
    ): Page<WorldEventSyncPayload> {
        if (matchColumn != BOOK_ID_COLUMN) {
            return super.pullByIds(
                userId = userId,
                matchColumn = matchColumn,
                matchValues = matchValues,
                extraWhere = extraWhere,
            )
        }
        val eventIds =
            suspendTransaction(db) {
                matchValues
                    // The query binds each chunk three times (home, anchor, series membership).
                    .chunked(SQLITE_IN_CHUNK / BINDS_PER_TOUCHING_QUERY)
                    .flatMap { chunk ->
                        db.worldEventsQueries.selectIdsTouchingBooks(chunk, chunk, chunk).executeAsList()
                    }.distinct()
            }
        val items = mutableListOf<WorldEventSyncPayload>()
        for (chunk in eventIds.chunked(PULL_BY_ID_CHUNK)) {
            items +=
                super.pullByIds(userId = userId, matchColumn = "id", matchValues = chunk, extraWhere = extraWhere).items
        }
        return Page(items = items, nextCursor = null, hasMore = false)
    }

    // ── In-transaction helpers ──

    private fun TransactionWithReturn<*>.applyUpsert(
        upsert: WorldEventUpsert,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long?,
    ): AppError? {
        val before = readPayload(upsert.id.value)
        upsertRefusal(upsert, before)?.let { return it }
        val type = if (upsert.type == WorldEventType.UNKNOWN) checkNotNull(before).type else upsert.type
        WorldEventRules.contentProblem(upsert, type)?.let { return it }
        val home = WorldHome(upsert.homeSeriesId?.value, upsert.homeBookId?.value)
        integrity.anchorProblem(upsert.bookId?.value, home)?.let { return it }
        integrity
            .participantProblem(
                type,
                upsert.subjectEntityId?.value,
                upsert.objectEntityId?.value,
                home,
                requireLive = true,
            )?.let { return it }
        // The base stamps revision, created_at and updated_at; the stamps below are placeholders.
        val value =
            WorldEventSyncPayload(
                id = upsert.id.value,
                homeSeriesId = home.seriesId,
                homeBookId = home.bookId,
                bookId = upsert.bookId?.value,
                positionMs = upsert.positionMs,
                type = type,
                text = upsert.text,
                detail = upsert.detail,
                subjectEntityId = upsert.subjectEntityId?.value,
                objectEntityId = upsert.objectEntityId?.value,
                createdBy = if (before == null) actor?.value else before.createdBy,
                updatedBy = actor?.value,
                revision = before?.revision ?: 0L,
                updatedAt = 0L,
                createdAt = before?.createdAt ?: 0L,
                deletedAt = null,
            )
        rewrite(
            before = before,
            after = value,
            op = if (before == null) StoryWorldOp.CREATE else StoryWorldOp.UPDATE,
            actor = actor,
            ctx = ctx,
            revision = revision,
        )
        return null
    }

    private fun TransactionWithReturn<*>.applyDelete(
        id: WorldEventId,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long?,
    ): AppError? {
        val before = liveOrNull(id.value) ?: return WorldEventError.NotFound(debugInfo = "event=${id.value}")
        tombstone(before = before, op = StoryWorldOp.DELETE, actor = actor, ctx = ctx, revision = revision)
        return null
    }

    /** Why [upsert] may not be written over [before] (the stored row, or null on create), or null when it may. */
    private fun upsertRefusal(
        upsert: WorldEventUpsert,
        before: WorldEventSyncPayload?,
    ): AppError? =
        when {
            before == null -> {
                WorldEventRules.creationProblem(upsert.type)
            }

            before.deletedAt != null -> {
                WorldEventError.NotFound(debugInfo = "event=${upsert.id.value} is deleted")
            }

            before.homeSeriesId != upsert.homeSeriesId?.value || before.homeBookId != upsert.homeBookId?.value -> {
                ValidationError(message = "An event stays in the series or book it was created in.")
            }

            else -> {
                null
            }
        }

    private fun liveOrNull(id: String): WorldEventSyncPayload? = readPayload(id)?.takeIf { it.deletedAt == null }

    /**
     * Writes [after] over [before] (null on create), records [op], mirrors the frame; returns the recorded entry.
     * [revision] is one the caller already took, or null to take a fresh one.
     */
    private fun TransactionWithReturn<*>.rewrite(
        before: WorldEventSyncPayload?,
        after: WorldEventSyncPayload,
        op: StoryWorldOp,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long? = null,
    ): WorldEventChange {
        val (saved, event) = upsertEventInOpenTransaction(after, ctx.suppressed, revision = revision)
        if (!ctx.suppressed) captureAfterCommit(ctx.capture, event)
        return history.record(
            eventId = saved.id,
            op = op,
            actor = actor,
            occurredAt = event.occurredAt,
            revision = event.revision,
            before = before,
            after = saved,
        )
    }

    /**
     * Tombstones [before], drops its mentions, records [op] with the tombstone as `after`; returns the entry.
     * [revision] is one the caller already took, or null to take a fresh one.
     */
    private fun TransactionWithReturn<*>.tombstone(
        before: WorldEventSyncPayload,
        op: StoryWorldOp,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long? = null,
    ): WorldEventChange {
        val event: SyncEvent.Deleted =
            checkNotNull(softDeleteInOpenTransaction(WorldEventId(before.id), ctx.suppressed, revision = revision)) {
                "world event ${before.id} vanished mid-transaction"
            }
        db.worldEventMentionsQueries.deleteForEvent(before.id)
        if (!ctx.suppressed) captureAfterCommit(ctx.capture, event)
        return history.record(
            eventId = before.id,
            op = op,
            actor = actor,
            occurredAt = event.occurredAt,
            revision = event.revision,
            before = before,
            after = readPayload(before.id),
        )
    }

    private fun hydrate(rows: List<World_events>): List<WorldEventSyncPayload> {
        if (rows.isEmpty()) return emptyList()
        val mentions =
            rows
                .map { it.id }
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.worldEventMentionsQueries.selectForEvents(chunk).executeAsList() }
                .groupBy({ it.event_id }, { it.entity_id })
        return rows.map { it.toPayload(mentions[it.id].orEmpty()) }
    }

    /**
     * The revision a bulk write took first — making its transaction the writer before it reads anything — handed
     * to its first row write; every later row takes a fresh one.
     */
    private class RevisionLease(
        private var lead: Long?,
    ) {
        fun take(): Long? = lead.also { lead = null }
    }

    /** The firehose suppression marker and the frame capture, read once in the suspend scope. */
    internal data class WriteContext(
        val suppressed: Boolean,
        val capture: FrameCapture?,
    )

    private suspend fun writeContext(): WriteContext {
        val context = currentCoroutineContext()
        return WriteContext(suppressed = context[FirehoseSuppressed.Key] != null, capture = context[FrameCapture.Key])
    }

    private fun World_events.toPayload(mentionIds: List<String>): WorldEventSyncPayload =
        WorldEventSyncPayload(
            id = id,
            homeSeriesId = home_series_id,
            homeBookId = home_book_id,
            bookId = book_id,
            positionMs = position_ms,
            type = WorldEventType.fromName(type.uppercase()),
            text = text,
            detail = detail,
            subjectEntityId = subject_entity_id,
            objectEntityId = object_entity_id,
            mentionIds = mentionIds,
            createdBy = created_by,
            updatedBy = updated_by,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private fun WorldEventType.storageName(): String = name.lowercase()

    private companion object {
        /** Bound variables per IN list, under SQLite's historical 999 limit with headroom. */
        const val SQLITE_IN_CHUNK = 900

        /** Event ids per access-filtered id match in [pullByIds] (its access clause binds variables too). */
        const val PULL_BY_ID_CHUNK = 500

        /** [pullByIds]' book-touching query binds each book-id chunk this many times. */
        const val BINDS_PER_TOUCHING_QUERY = 3

        /** The column `TargetedMatch.BOOK_ID` resolves to (see SyncPullSupport). */
        const val BOOK_ID_COLUMN = "book_id"
    }
}
