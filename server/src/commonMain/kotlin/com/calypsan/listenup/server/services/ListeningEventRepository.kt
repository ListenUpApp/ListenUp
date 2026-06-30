package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.core.ListeningEventId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Listening_events
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock
import kotlinx.serialization.KSerializer

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
        domainName = "listening_events",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    /**
     * Overrides the base upsert to fire `statsRecorder.record(StatsEvent.ListeningSessionClosed(...))`
     * after the event row commits. See the class KDoc for why this is de-nested (sequential,
     * post-commit) rather than nested in one transaction.
     *
     * The stats hook fires only when the event row did not already exist: the pending-op queue
     * delivers at-least-once, and incremental stats accrual must stay idempotent under a re-fire of
     * an already-committed event id. Existence is sampled in its own transaction before the write;
     * on the single serialized SQLite connection this is the same row-state the base then observes.
     */
    override suspend fun upsert(
        value: ListeningEventSyncPayload,
        clientOpId: String?,
        userId: String?,
    ): AppResult<ListeningEventSyncPayload> {
        // Sample existence before the write: incremental stats accrual must run exactly once per
        // event id, so the hook fires only when the row did not already exist (idempotent replay).
        val alreadyExisted = suspendTransaction(db) { readPayload(value.id) != null }
        val result = super.upsert(value, clientOpId, userId)
        if (result is AppResult.Success && userId != null && !alreadyExisted) {
            statsRecorder?.record(StatsEvent.ListeningSessionClosed(userId = userId, span = value))
        }
        return result
    }

    override val elementSerializer: KSerializer<ListeningEventSyncPayload> =
        ListeningEventSyncPayload.serializer()

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
    }
}
