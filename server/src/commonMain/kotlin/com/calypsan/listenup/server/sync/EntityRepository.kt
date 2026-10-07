package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.server.db.sqldelight.Entities
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * The `entities` syncable repository: Story World world data, dual-homed under a series or a book.
 *
 * **Every write is one transaction** that reads the current row, decides, writes the row, and records a
 * [StoryWorldHistory] row — so a history entry's `before` is exactly the row the write replaced (next's
 * bug: its read and write ran in separate transactions, so a concurrent write could slip between them and
 * tear the snapshot), and a history row exists iff its change does.
 *
 * **Arrival order wins**, as for every other syncable domain: a write is a full-payload overwrite, and
 * the base stamps `updated_at` with the server's clock. An incoming payload's `updatedAt` is ignored.
 *
 * **Access-gated.** [driver] carries the caller's visibility subquery into the filtered pull
 * (`ACCESS_FILTERS`); [minimizeTombstone] blanks everything a tombstone could leak; [pullByIds] answers
 * the scoped `AccessChanged` delta's book-id match itself (see its KDoc).
 */
class EntityRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<EntitySyncPayload, EntityId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.ENTITIES,
        clock = clock,
    ) {
    private val history = StoryWorldHistory(db)

    override val EntitySyncPayload.id: EntityId
        get() = EntityId(id)

    override fun idAsString(id: EntityId): String = id.value

    /** A tombstone skips the access gate, so it carries identity only — not even the kind. */
    override fun minimizeTombstone(payload: EntitySyncPayload): EntitySyncPayload =
        payload.copy(
            kind = EntityKind.UNKNOWN,
            name = "",
            descriptor = null,
            parentId = null,
            homeSeriesId = null,
            homeBookId = null,
            imageRef = null,
            createdBy = null,
            updatedBy = null,
        )

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

    override fun readPayload(idStr: String): EntitySyncPayload? =
        db.entitiesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<EntitySyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.entitiesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    override fun writePayload(
        value: EntitySyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.entitiesQueries.update(
                kind = value.kind.storageName(),
                name = value.name,
                descriptor = value.descriptor,
                parent_id = value.parentId,
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                image_ref = value.imageRef,
                updated_by = value.updatedBy,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.entitiesQueries.insert(
                id = value.id,
                kind = value.kind.storageName(),
                name = value.name,
                descriptor = value.descriptor,
                parent_id = value.parentId,
                home_series_id = value.homeSeriesId,
                home_book_id = value.homeBookId,
                image_ref = value.imageRef,
                created_by = value.createdBy,
                updated_by = value.updatedBy,
                created_at = now,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
            )
        }
    }

    // ── Reads ──

    /** The entity with [id], live or tombstoned, or null. */
    suspend fun findById(id: EntityId): EntitySyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /** Live entities homed on [seriesId], by name. */
    suspend fun listLiveForSeries(seriesId: SeriesId): List<EntitySyncPayload> =
        suspendTransaction(db) {
            db.entitiesQueries
                .selectLiveBySeries(seriesId.value)
                .executeAsList()
                .map { it.toPayload() }
        }

    /** Live entities homed on [bookId], by name. */
    suspend fun listLiveForBook(bookId: BookId): List<EntitySyncPayload> =
        suspendTransaction(db) {
            db.entitiesQueries
                .selectLiveByBook(bookId.value)
                .executeAsList()
                .map { it.toPayload() }
        }

    /** [id]'s history, newest first. */
    suspend fun listHistory(id: EntityId): List<EntityChange> = suspendTransaction(db) { history.listFor(id) }

    /** One history entry, or null. */
    suspend fun findChange(changeId: StoryWorldHistoryId): EntityChange? =
        suspendTransaction(db) { history.find(changeId) }

    // ── Writes (each one transaction: read, decide, write, record history) ──

    /**
     * Creates or edits [value] as a full overwrite. Records CREATE or UPDATE, whose `before` is the row this
     * write replaced.
     *
     * The integrity rules are decided here, against that same row, inside the write's transaction — never
     * from a separate read a concurrent write could overtake:
     * - the home is fixed at creation; an edit naming another home is a [ValidationError];
     * - [EntityKind.UNKNOWN] is refused on create, and keeps the stored kind on an edit (an older client
     *   can't clobber a kind it doesn't know);
     * - authorship is the server's: `createdBy` is [actor] on create and never changes, `updatedBy` is
     *   [actor] on every write — whatever the payload claims;
     * - an edit carries the stored `imageRef` over;
     * - a deleted entity stays deleted: an edit to it is [EntityError.NotFound] (a late offline edit can't
     *   undo a curator's delete; only [revert] or a book re-add revives);
     * - a parent [EntityParentRules] rejects is refused.
     */
    suspend fun upsertEntity(
        value: EntitySyncPayload,
        actor: UserId?,
    ): AppResult<EntitySyncPayload> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            // Take the revision first: the transaction becomes the writer before it reads `before`, so no
            // other write can slip between the read and this write.
            val rev = nextRevision()
            val before = readPayload(value.id)
            upsertRefusal(value, before)?.let { return@suspendTransaction AppResult.Failure(it) }
            val resolved =
                value.copy(
                    kind = if (value.kind == EntityKind.UNKNOWN) checkNotNull(before).kind else value.kind,
                    imageRef = if (before == null) value.imageRef else before.imageRef,
                    createdBy = if (before == null) actor?.value else before.createdBy,
                    updatedBy = actor?.value,
                    deletedAt = null,
                )
            parentProblem(resolved)?.let { return@suspendTransaction AppResult.Failure(it) }
            val (saved, event) = upsertEventInOpenTransaction(resolved, ctx.suppressed, revision = rev)
            if (!ctx.suppressed) captureAfterCommit(ctx.capture, event)
            history.record(
                entityId = saved.id,
                op = if (before == null) StoryWorldOp.CREATE else StoryWorldOp.UPDATE,
                actor = actor,
                occurredAt = event.occurredAt,
                revision = event.revision,
                before = before,
                after = saved,
            )
            AppResult.Success(saved)
        }
    }

    /** Tombstones the live entity [id] and records DELETE; [EntityError.NotFound] when absent or deleted. */
    suspend fun deleteEntity(
        id: EntityId,
        actor: UserId?,
    ): AppResult<Unit> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val rev = nextRevision()
            val before =
                liveOrNull(id.value)
                    ?: return@suspendTransaction AppResult.Failure(
                        EntityError.NotFound(debugInfo = "entity=${id.value}"),
                    )
            tombstone(before, StoryWorldOp.DELETE, actor, ctx, revision = rev)
            AppResult.Success(Unit)
        }
    }

    /**
     * Folds [source] into [target]: same kind and home required; [target] may not sit beneath [source].
     * The source's live children move to the target (UPDATE each), then the source is tombstoned (MERGE).
     */
    suspend fun mergeEntities(
        source: EntityId,
        target: EntityId,
        actor: UserId?,
    ): AppResult<EntitySyncPayload> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            val from = liveOrNull(source.value)
            val into = liveOrNull(target.value)
            if (from == null || into == null) {
                return@suspendTransaction AppResult.Failure(
                    EntityError.NotFound(debugInfo = "source=${source.value} target=${target.value}"),
                )
            }
            mergeRefusal(from, into)?.let { return@suspendTransaction AppResult.Failure(it) }
            for (childId in db.entitiesQueries.selectLiveChildIds(from.id).executeAsList()) {
                val child = checkNotNull(readPayload(childId))
                rewrite(
                    child,
                    child.copy(parentId = into.id, updatedBy = actor?.value),
                    StoryWorldOp.UPDATE,
                    actor,
                    ctx,
                    revision = lease.take(),
                )
            }
            tombstone(from, StoryWorldOp.MERGE, actor, ctx, revision = lease.take())
            AppResult.Success(into)
        }
    }

    /**
     * Restores the `before` of [changeId] as a new forward write and records REVERT: a CREATE reverts to a
     * tombstone; anything else restores (and revives) the earlier snapshot, keeping its home.
     *
     * Whether the revert is structural is decided here, inside the write's transaction, against the
     * current row — never from a read a concurrent delete could overtake. Reverting a CREATE, DELETE or
     * MERGE, a REVERT that crossed between live and deleted, or anything of an entity that is deleted
     * now, is structural; without [allowStructural] (the caller's curate capability) it is
     * [AuthError.PermissionDenied], so a content revert can never revive a deleted entity.
     */
    suspend fun revert(
        changeId: StoryWorldHistoryId,
        actor: UserId?,
        allowStructural: Boolean,
    ): AppResult<EntityChange> {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val rev = nextRevision()
            val change =
                history.find(changeId)
                    ?: return@suspendTransaction AppResult.Failure(
                        EntityError.HistoryNotFound(debugInfo = "change=${changeId.value}"),
                    )
            val current =
                readPayload(change.entityId.value)
                    ?: return@suspendTransaction AppResult.Failure(
                        EntityError.NotFound(debugInfo = "entity=${change.entityId.value}"),
                    )
            if (!allowStructural && (current.deletedAt != null || !change.isContentEdit())) {
                return@suspendTransaction AppResult.Failure(
                    AuthError.PermissionDenied(debugInfo = "structural revert of change=${changeId.value}"),
                )
            }
            val restore =
                change.before
                    ?: return@suspendTransaction AppResult.Success(
                        tombstone(current, StoryWorldOp.REVERT, actor, ctx, revision = rev),
                    )
            val restored =
                restore.copy(
                    homeSeriesId = current.homeSeriesId,
                    homeBookId = current.homeBookId,
                    revision = current.revision,
                    updatedBy = actor?.value,
                    deletedAt = null,
                )
            parentProblem(restored)?.let { return@suspendTransaction AppResult.Failure(it) }
            AppResult.Success(rewrite(current, restored, StoryWorldOp.REVERT, actor, ctx, revision = rev))
        }
    }

    /** True when this change edited a live entry's content and left it live; its revert does the same. */
    private fun EntityChange.isContentEdit(): Boolean =
        when (op) {
            StoryWorldOp.UPDATE -> true
            StoryWorldOp.REVERT -> before != null && before?.deletedAt == null && after != null && after?.deletedAt == null
            StoryWorldOp.CREATE, StoryWorldOp.DELETE, StoryWorldOp.MERGE -> false
        }

    // ── Book removal cascade (BookRepository.softDelete / reviveByIds) ──
    // Like every write here, each takes its revision before it reads, so the transaction is the writer
    // first. A cheap read-only check runs before that, so a book with no entities burns no revision; the
    // write transaction then re-reads, so a write that overtakes the check is still seen.

    /** Tombstones every live entity homed on [bookId] (DELETE, no actor). Returns how many. */
    suspend fun softDeleteAllForBook(bookId: String): Int {
        if (!suspendTransaction(db) { db.entitiesQueries.hasLiveForBook(bookId).executeAsOne() }) return 0
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            val live = db.entitiesQueries.selectLiveIdsForBook(bookId).executeAsList()
            live.forEach { id ->
                tombstone(checkNotNull(readPayload(id)), StoryWorldOp.DELETE, null, ctx, revision = lease.take())
            }
            live.size
        }
    }

    /**
     * Revives the entities of [bookIds] that a book removal tombstoned (REVERT, no actor): exactly those
     * whose newest history row is the cascade's DELETE with no actor. History, not a `deleted_at` floor,
     * decides — a re-delete of an already-removed book re-stamps the book's `deleted_at`, and a curator's
     * later revert or delete is a newer row with an actor, so a deliberate delete stays deleted.
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
                rewrite(before, before.copy(deletedAt = null), StoryWorldOp.REVERT, null, ctx, revision = lease.take())
            }
            dead.size
        }
    }

    private fun cascadeDeletedFor(bookIds: List<String>): List<String> =
        bookIds.chunked(SQLITE_IN_CHUNK).flatMap { chunk ->
            db.entitiesQueries.selectCascadeDeletedForBooks(chunk).executeAsList()
        }

    // ── Series merge (SeriesServiceImpl.mergeSeries / SeriesMergeReceipts.undo) ──

    /**
     * Records [source]'s live entities against [receiptId], then re-homes each to [target] (UPDATE, no
     * actor: a cascade of the series merge). Their parent links move with them — a parent always shares
     * its child's home, so the whole tree moves at once. Returns how many moved.
     */
    suspend fun rehomeForSeriesMerge(
        receiptId: String,
        source: SeriesId,
        target: SeriesId,
    ): Int {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            db.seriesMergeReceiptsQueries.snapshotSourceEntities(receipt_id = receiptId, source_id = source.value)
            val moving = db.seriesMergeReceiptsQueries.selectReceiptEntityIds(receiptId).executeAsList()
            moving.forEach { id ->
                val before = checkNotNull(readPayload(id))
                val after = before.copy(homeSeriesId = target.value)
                rewrite(before, after, StoryWorldOp.UPDATE, actor = null, ctx = ctx, revision = lease.take())
            }
            moving.size
        }
    }

    /**
     * Moves [receiptId]'s entities that are still live under [target] back to [source] (UPDATE, no actor),
     * each in its current state — an edit made since the merge survives. An entity created under [target]
     * since the merge is not on the receipt, so it stays.
     *
     * A parent link may not span two homes (it would leak visibility), and the tree may have been
     * rearranged since the merge: a returning entity whose parent stays in [target] loses that parent, and
     * an entity staying in [target] whose parent returns to [source] loses its parent too (UPDATE each).
     * Returns how many moved back.
     */
    suspend fun restoreForSeriesMergeUndo(
        receiptId: String,
        source: SeriesId,
        target: SeriesId,
    ): Int {
        val ctx = writeContext()
        return suspendTransaction(db) {
            val lease = RevisionLease(nextRevision())
            val returning =
                db.seriesMergeReceiptsQueries
                    .selectReceiptEntityIds(receiptId)
                    .executeAsList()
                    .mapNotNull { id -> liveOrNull(id)?.takeIf { it.homeSeriesId == target.value } }
            val returningIds = returning.mapTo(HashSet()) { it.id }
            val strandedChildren =
                returningIds
                    .flatMap { id -> db.entitiesQueries.selectLiveChildIds(id).executeAsList() }
                    .filterNot { it in returningIds }
            strandedChildren.forEach { id ->
                val before = checkNotNull(readPayload(id))
                rewrite(before, before.copy(parentId = null), StoryWorldOp.UPDATE, null, ctx, lease.take())
            }
            returning.forEach { before ->
                val after =
                    before.copy(
                        homeSeriesId = source.value,
                        parentId = before.parentId?.takeIf { it in returningIds },
                    )
                rewrite(before, after, StoryWorldOp.UPDATE, actor = null, ctx = ctx, revision = lease.take())
            }
            returning.size
        }
    }

    // ── Targeted pull ──

    /**
     * The scoped `AccessChanged` delta asks this domain by **book id** (`TargetedMatch.BOOK_ID`), but an
     * entity has no `book_id` column: it is homed on a book or on a series that contains books. This
     * resolves the asked-about books to every entity they touch (tombstones included), then lets the base
     * answer by entity id — still access-filtered by [extraWhere], so a hidden entity never comes back
     * and the client tombstones it.
     */
    override suspend fun pullByIds(
        userId: String?,
        matchColumn: String,
        matchValues: List<String>,
        extraWhere: SqlFragment?,
    ): Page<EntitySyncPayload> {
        if (matchColumn != BOOK_ID_COLUMN) return super.pullByIds(userId, matchColumn, matchValues, extraWhere)
        val entityIds =
            suspendTransaction(db) {
                matchValues
                    // The query binds each chunk twice (both halves of its UNION), so half a chunk per call.
                    .chunked(SQLITE_IN_CHUNK / 2)
                    .flatMap { chunk -> db.entitiesQueries.selectIdsTouchingBooks(chunk, chunk).executeAsList() }
                    .distinct()
            }
        // One series can hold far more entities than the caller asked books for, so the id match is
        // chunked to keep each IN list well inside SQLite's bound-variable limit.
        val items = mutableListOf<EntitySyncPayload>()
        for (chunk in entityIds.chunked(PULL_BY_ID_CHUNK)) {
            items += super.pullByIds(userId, "id", chunk, extraWhere).items
        }
        return Page(items = items, nextCursor = null, hasMore = false)
    }

    // ── In-transaction helpers ──

    private fun liveOrNull(id: String): EntitySyncPayload? = readPayload(id)?.takeIf { it.deletedAt == null }

    private fun storedParentOf(id: String): String? =
        db.entitiesQueries
            .selectParentId(id)
            .executeAsOneOrNull()
            ?.parent_id

    private fun parentProblem(value: EntitySyncPayload): EntityError? =
        value.parentId?.let { parentId -> EntityParentRules.check(value, liveOrNull(parentId), ::storedParentOf) }

    /** Why [value] may not be written over [before] (the stored row, or null on create), or null when it may. */
    private fun upsertRefusal(
        value: EntitySyncPayload,
        before: EntitySyncPayload?,
    ) = when {
        before == null && value.kind == EntityKind.UNKNOWN -> {
            ValidationError(message = "Choose what kind of entry this is.", field = "kind")
        }

        before == null -> {
            null
        }

        before.deletedAt != null -> {
            EntityError.NotFound(debugInfo = "entity=${value.id} is deleted")
        }

        before.homeSeriesId != value.homeSeriesId || before.homeBookId != value.homeBookId -> {
            ValidationError(message = "An entry stays in the series or book it was created in.")
        }

        else -> {
            null
        }
    }

    /** Why [from] may not fold into [into], or null when the merge may proceed. */
    private fun mergeRefusal(
        from: EntitySyncPayload,
        into: EntitySyncPayload,
    ) = when {
        from.id == into.id -> {
            ValidationError(message = "An entry can't be merged into itself.")
        }

        from.kind != into.kind -> {
            EntityError.KindMismatchOnMerge(debugInfo = "${from.kind} into ${into.kind}")
        }

        from.homeSeriesId != into.homeSeriesId || from.homeBookId != into.homeBookId -> {
            ValidationError(message = "Only entries in the same series or book can be merged.")
        }

        EntityParentRules.isAncestor(from.id, into.parentId, ::storedParentOf) -> {
            EntityError.CycleDetected(debugInfo = "target=${into.id} sits beneath source=${from.id}")
        }

        else -> {
            null
        }
    }

    /**
     * Writes [after] over [before], records [op], mirrors the frame; returns the recorded entry. [revision] is
     * one the caller already took (see [upsertEntity]), or null to take a fresh one.
     */
    private fun TransactionWithReturn<*>.rewrite(
        before: EntitySyncPayload,
        after: EntitySyncPayload,
        op: StoryWorldOp,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long? = null,
    ): EntityChange {
        val (saved, event) = upsertEventInOpenTransaction(after, ctx.suppressed, revision = revision)
        if (!ctx.suppressed) captureAfterCommit(ctx.capture, event)
        return history.record(saved.id, op, actor, event.occurredAt, event.revision, before, saved)
    }

    /**
     * Tombstones [before], records [op] with the tombstone as `after`; returns the recorded entry. [revision]
     * is one the caller already took, or null to take a fresh one.
     */
    private fun TransactionWithReturn<*>.tombstone(
        before: EntitySyncPayload,
        op: StoryWorldOp,
        actor: UserId?,
        ctx: WriteContext,
        revision: Long? = null,
    ): EntityChange {
        val event: SyncEvent.Deleted =
            checkNotNull(softDeleteInOpenTransaction(EntityId(before.id), ctx.suppressed, revision = revision)) {
                "entity ${before.id} vanished mid-transaction"
            }
        if (!ctx.suppressed) captureAfterCommit(ctx.capture, event)
        return history.record(before.id, op, actor, event.occurredAt, event.revision, before, readPayload(before.id))
    }

    /**
     * The revision a bulk write took first — making its transaction the writer before it reads anything —
     * handed to its first row write; every later row takes a fresh one.
     */
    private class RevisionLease(
        private var lead: Long?,
    ) {
        fun take(): Long? = lead.also { lead = null }
    }

    /** The firehose suppression marker and the frame capture, read once in the suspend scope. */
    private class WriteContext(
        val suppressed: Boolean,
        val capture: FrameCapture?,
    )

    private suspend fun writeContext(): WriteContext {
        val context = currentCoroutineContext()
        return WriteContext(suppressed = context[FirehoseSuppressed.Key] != null, capture = context[FrameCapture.Key])
    }

    private fun Entities.toPayload(): EntitySyncPayload =
        EntitySyncPayload(
            id = id,
            kind = EntityKind.fromName(kind.uppercase()),
            name = name,
            descriptor = descriptor,
            parentId = parent_id,
            homeSeriesId = home_series_id,
            homeBookId = home_book_id,
            imageRef = image_ref,
            createdBy = created_by,
            updatedBy = updated_by,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private fun EntityKind.storageName(): String = name.lowercase()

    private companion object {
        /** Bound variables per IN list, under SQLite's historical 999 limit with headroom. */
        const val SQLITE_IN_CHUNK = 900

        /** Entity ids per access-filtered id match in [pullByIds] (its access clause binds variables too). */
        const val PULL_BY_ID_CHUNK = 500

        /** The column `TargetedMatch.BOOK_ID` resolves to (see SyncPullSupport). */
        const val BOOK_ID_COLUMN = "book_id"
    }
}
