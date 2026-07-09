package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ListeningEventId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Listening_events
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import com.calypsan.listenup.server.util.KeyedMutex
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * One resolved playback session an ABS import intends to persist as a listening event — the batch
 * counterpart to a single [ListeningEventRepository.upsert] call. [event] carries the stable
 * `abs:<sessionId>` id, so a re-applied import re-upserts idempotently (append-only). The importer
 * collects these and hands the whole list to [ListeningEventRepository.upsertAllForImport] so the
 * writes commit in chunked transactions instead of an existence-read + write commit pair per row.
 */
data class ImportListeningEventWrite(
    val userId: String,
    val event: ListeningEventSyncPayload,
)

/**
 * SQLDelight syncable repository for per-user listening events (Playback P2).
 *
 * One row per closed playback span — an uninterrupted period of audio playback with a single
 * `playbackSpeed`. The domain is append-only: [writePayload] inserts on first write and advances
 * only `revision`/`updatedAt`/`clientOpId` on a re-upsert of the same id (idempotent pending-op
 * replay).
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest` call routes through
 * the per-user dimension of the [SqlSyncableRepository] base (the [ShelfRepository] pattern).
 *
 * `idAsString(ListeningEventId) = id.value` is load-bearing — Kotlin's default `toString()` on a
 * value class returns `"ListeningEventId(value=foo)"`, which would corrupt every column the id is
 * written to.
 *
 * **Hooks.** [upsert] fires `statsRecorder.record(StatsEvent.ListeningSessionClosed(...))` on first
 * insert — the single choke-point that materializes `user_stats`, refreshes `public_profiles`, and
 * records the `LISTENING_SESSION` activity. It is **de-nested**: the event row commits in
 * [SqlSyncableRepository.upsert]'s own transaction first, then [StatsRecorder.record] runs afterwards
 * in its own transactions (the `BookServiceImpl.setBookGenres` pattern). De-nesting is required, not
 * merely convenient, because the project's SQLDelight `suspendTransaction` body is a plain
 * (non-suspend) `transactionWithResult` lambda — a suspend hook call (which opens its own transaction,
 * and additionally reads the Exposed `users` table for the timezone) cannot nest inside it, so it
 * would never compile, let alone run as a savepoint. Running it after the event commits in a separate
 * transaction (on the single SQLite connection, one at a time) is `SQLITE_BUSY`-free. Atomicity-on-crash
 * is preserved by design: the stats hook fires only on first insert (`!alreadyExisted`), and stats
 * accrual self-heals via `UserStatsBackfillService` — so a crash between the event commit and the
 * stats write is recoverable, exactly as the at-least-once pending-op queue already assumes.
 *
 * The existence sample and the write are made atomic per user by [firstInsertLock] (a [KeyedMutex]
 * distinct from [StatsRecorder]'s own per-user lock — see [upsert]), so two concurrent replays of the
 * same event id can never both observe "not yet inserted" and both fire the stats hook.
 */
class ListeningEventRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    private val statsRecorder: StatsRecorder? = null,
) : SqlSyncableRepository<ListeningEventSyncPayload, ListeningEventId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.LISTENING_EVENTS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    /**
     * Serializes the existence-sample + write pair per user, so two concurrent upserts of the same
     * event id (an at-least-once pending-op replay racing itself) cannot both sample "not yet
     * inserted" and both fire the stats hook. A separate instance from [StatsRecorder]'s own
     * per-user lock — the two are never held at once (this lock is released before [StatsRecorder]'s
     * is acquired), so a lock-ordering deadlock between them is structurally impossible.
     */
    private val firstInsertLock = KeyedMutex()

    /**
     * Overrides the base upsert to fire `statsRecorder.record(StatsEvent.ListeningSessionClosed(...))`
     * after the event row commits. See the class KDoc for why this is de-nested (sequential,
     * post-commit) rather than nested in one transaction.
     *
     * The stats hook fires only when the event row did not already exist: the pending-op queue
     * delivers at-least-once, and incremental stats accrual must stay idempotent under a re-fire of
     * an already-committed event id. [firstInsertLock] makes the existence sample and the write
     * atomic per user, so two concurrent replays of the same event id cannot both observe "not yet
     * inserted". The lock is released BEFORE the stats cascade runs — [StatsRecorder] serializes
     * itself with its own per-user lock, and [KeyedMutex] is not reentrant.
     */
    override suspend fun upsert(
        value: ListeningEventSyncPayload,
        clientOpId: String?,
        userId: String?,
    ): AppResult<ListeningEventSyncPayload> {
        // Without a user, no stats hook can fire — keep the plain path.
        if (userId == null) return super.upsert(value, clientOpId, userId)

        val (alreadyExisted, result) =
            firstInsertLock.withLock(userId) {
                val existed = suspendTransaction(db) { readPayload(value.id) != null }
                existed to super.upsert(value, clientOpId, userId)
            }
        if (result is AppResult.Success && !alreadyExisted) {
            statsRecorder?.record(StatsEvent.ListeningSessionClosed(userId = userId, span = value))
        }
        return result
    }

    /**
     * Batch-upsert [events] for an ABS import — the chunked-transaction counterpart to [upsert]. A
     * per-session [upsert] loop costs two commits per row (an existence-read transaction plus the
     * write transaction); a full history import runs tens of thousands. This collapses the burst the
     * same way [BookRepository.resolveOrInsertAll] does — prepare, then chunked writes — while
     * preserving [upsert]'s idempotency and first-insert stats-hook semantics exactly:
     *
     *  1. **PREPARE (per user, under [firstInsertLock]).** Batch-read which of that user's event ids
     *     already exist (chunked `IN (…)`), then decide the first-insert flag per row in memory. A
     *     running seen-set folds intra-batch duplicate ids so the second occurrence is treated as
     *     already-inserted — matching the single-row loop's read-your-writes.
     *  2. **WRITE (chunked synchronous transactions).** Process the rows in chunks of
     *     [PERSIST_CHUNK_SIZE]; each chunk is ONE [suspendTransaction] whose body calls
     *     [upsertInOpenTransaction] per row — O(chunks) write transactions, not O(rows). [suppressed]
     *     is read ONCE in the suspend context and threaded in, exactly as the batched book path does.
     *  3. **HOOKS (post-commit, outside the lock).** Fire `StatsEvent.ListeningSessionClosed` for each
     *     id that did NOT previously exist — the same choke-point [upsert] fires on first insert.
     *
     * **Idempotency / lock choice.** The existence-read and the write for a user's whole batch are
     * held together under [firstInsertLock] for that user. The import job is single-threaded, but an
     * imported event uses a stable `abs:<id>` that a *concurrent live sync* could theoretically also
     * deliver; widening the single-row lock to cover the batch window preserves the exact property
     * [upsert] guarantees — two deliverers of the same id can never both observe "not yet inserted"
     * and both fire the stats hook. The lock is released BEFORE the hooks run ([KeyedMutex] is not
     * reentrant and [StatsRecorder] serializes itself). A no-op on an empty [events].
     */
    suspend fun upsertAllForImport(events: List<ImportListeningEventWrite>) {
        if (events.isEmpty()) return
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null

        events.groupBy { it.userId }.forEach { (userId, userEvents) ->
            val newlyInserted =
                firstInsertLock.withLock(userId) {
                    val existingIds = existingIds(userEvents.map { it.event.id })
                    val seen = HashSet<String>(userEvents.size)
                    val firstInserts = ArrayList<ListeningEventSyncPayload>()
                    for (write in userEvents) {
                        val existedBefore = write.event.id in existingIds || write.event.id in seen
                        seen += write.event.id
                        if (!existedBefore) firstInserts += write.event
                    }
                    for (chunk in userEvents.chunked(PERSIST_CHUNK_SIZE)) {
                        suspendTransaction<Unit>(db) {
                            chunk.forEach {
                                upsertInOpenTransaction(it.event, suppressed, clientOpId = null, userId = userId)
                            }
                        }
                    }
                    firstInserts
                }
            newlyInserted.forEach {
                statsRecorder?.record(StatsEvent.ListeningSessionClosed(userId = userId, span = it))
            }
        }
    }

    /** The subset of [ids] whose listening-event row already exists, read in chunked `IN (…)` batches. */
    private suspend fun existingIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return suspendTransaction(db) {
            ids
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.listeningEventsQueries.selectByIds(chunk).executeAsList() }
                .map { it.id }
                .toSet()
        }
    }

    override fun idAsString(id: ListeningEventId): String = id.value

    override val ListeningEventSyncPayload.id: ListeningEventId
        get() = ListeningEventId(this.id)

    override fun ListeningEventSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.listeningEventsQueries].
     * Canonical user-scoped adapter shape (see [ShelfRepository]).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.listeningEventsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.listeningEventsQueries
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
                db.listeningEventsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.listeningEventsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.listeningEventsQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.listeningEventsQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): ListeningEventSyncPayload? =
        db.listeningEventsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ListeningEventSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.listeningEventsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: ListeningEventSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "ListeningEventRepository.writePayload requires a userId" }
        if (existed) {
            // Append-only domain — a duplicate upsert of the same id advances revision/updatedAt/
            // clientOpId so the pending-op queue's idempotent re-fire round-trips correctly, but
            // domain fields are never mutated.
            db.listeningEventsQueries.updateSyncColumns(
                revision = rev,
                updated_at = now,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.listeningEventsQueries.insert(
                id = value.id,
                user_id = userId,
                book_id = value.bookId,
                start_position_ms = value.startPositionMs,
                end_position_ms = value.endPositionMs,
                started_at = value.startedAt,
                ended_at = value.endedAt,
                // `playback_speed` is REAL (Double) in SQLite; the wire payload uses Float.
                playback_speed = value.playbackSpeed.toDouble(),
                tz = value.tz,
                device_label = value.deviceLabel,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: ListeningEventId): String = idAsString(id)

    private fun Listening_events.toSyncPayload(): ListeningEventSyncPayload =
        ListeningEventSyncPayload(
            id = id,
            bookId = book_id,
            startPositionMs = start_position_ms,
            endPositionMs = end_position_ms,
            startedAt = started_at,
            endedAt = ended_at,
            playbackSpeed = playback_speed.toFloat(),
            tz = tz,
            deviceLabel = device_label,
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

        /** Import rows per write transaction — one [suspendTransaction] commits a whole chunk. */
        const val PERSIST_CHUNK_SIZE = 200
    }
}
