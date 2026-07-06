package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.PlaybackPositionId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Playback_positions
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * SQLDelight syncable repository for per-user playback positions (Playback P1).
 *
 * One row per `(userId, bookId)` pair — the current resume point for one user's progress through
 * one book. `lastPlayedAt`-wins conflict resolution: a write with a stale `lastPlayedAt` (less
 * than the stored value) is silently dropped so a stale offline write never clobbers a fresher
 * position from another device.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest` call routes
 * through the per-user dimension of the [SqlSyncableRepository] base (the [ShelfRepository]
 * pattern).
 *
 * `idAsString(PlaybackPositionId) = id.value` is load-bearing — Kotlin's default `toString()` on
 * a value class returns `"PlaybackPositionId(value=foo)"`, which would corrupt every column the
 * id is written to.
 *
 * **Hooks.** [recordPosition] fires the completion/start cascade. All hooks are **de-nested**: the
 * position row commits in [SqlSyncableRepository.upsert]'s own transaction first, then the hooks run
 * sequentially afterwards (the `BookServiceImpl.setBookGenres` pattern). De-nesting is required, not
 * merely convenient: the project's SQLDelight `suspendTransaction` body is a plain (non-suspend)
 * `transactionWithResult` lambda, so the suspend [statsRecorder] call — which opens its own
 * transactions — cannot nest inside it; and [activeSessionRepo] is still Exposed (`active_sessions`,
 * V17, out of this cluster's scope), so it would in any case deadlock on the SQLite write lock if run
 * while a SQLDelight write transaction were held. Running each hook after the position commits, on the
 * single serialized SQLite connection, is `SQLITE_BUSY`-free. The flip/start decision is taken with the
 * in-transaction `existed`/`priorFinished` view captured before the write, so it is unaffected by the
 * de-nesting.
 */
class PlaybackPositionRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    private val statsRecorder: StatsRecorder? = null,
    private val activeSessionRepo: ActiveSessionRepository? = null,
) : SqlSyncableRepository<PlaybackPositionSyncPayload, PlaybackPositionId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.PLAYBACK_POSITIONS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override fun idAsString(id: PlaybackPositionId): String = id.value

    override val PlaybackPositionSyncPayload.id: PlaybackPositionId
        get() = PlaybackPositionId(this.id)

    override fun PlaybackPositionSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.playbackPositionsQueries].
     * Canonical user-scoped adapter shape (see [ShelfRepository]).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.playbackPositionsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.playbackPositionsQueries
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
                db.playbackPositionsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.playbackPositionsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.playbackPositionsQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.playbackPositionsQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): PlaybackPositionSyncPayload? =
        db.playbackPositionsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<PlaybackPositionSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.playbackPositionsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: PlaybackPositionSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "PlaybackPositionRepository.writePayload requires a userId" }
        if (existed) {
            db.playbackPositionsQueries.update(
                position_ms = value.positionMs,
                last_played_at = value.lastPlayedAt,
                finished = value.finished.toDbLong(),
                playback_speed = value.playbackSpeed.toDouble(),
                current_chapter_id = value.currentChapterId,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.playbackPositionsQueries.insert(
                id = value.id,
                user_id = userId,
                book_id = value.bookId,
                position_ms = value.positionMs,
                last_played_at = value.lastPlayedAt,
                finished = value.finished.toDbLong(),
                playback_speed = value.playbackSpeed.toDouble(),
                current_chapter_id = value.currentChapterId,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Record a playback position for `(userId, bookId)`. `lastPlayedAt`-wins: if a row already
     * exists with a `lastPlayedAt >=` the incoming one, this is a no-op and the stored payload is
     * returned unchanged — a stale offline write never clobbers a fresher position from another
     * device.
     *
     * The position row commits via [SqlSyncableRepository.upsert]; the completion/start cascade hooks
     * are de-nested and fire sequentially afterwards (see the class KDoc).
     *
     * [startedBookOccurredAt] overrides ONLY the `STARTED_BOOK` activity date (both the new-start and
     * the re-read [StatsEvent.BookRestarted] branches). The ABS-import backfill uses it to date the
     * imported start strictly before the book's imported sessions; live callers leave it null and the
     * activity keeps using [lastPlayedAt]. Position `lastPlayedAt` semantics (the wins-guard, the
     * payload, the `BookCompleted` date) are untouched by this parameter.
     */
    suspend fun recordPosition(
        userId: String,
        bookId: String,
        positionMs: Long,
        lastPlayedAt: Long,
        finished: Boolean,
        playbackSpeed: Float,
        currentChapterId: String?,
        startedBookOccurredAt: Long? = null,
    ): AppResult<PlaybackPositionSyncPayload> {
        val existing = getPosition(userId, bookId)
        // lastPlayedAt-wins: a stale write is a no-op, returning the stored payload and firing no hooks.
        if (existing != null && existing.lastPlayedAt >= lastPlayedAt) {
            return AppResult.Success(existing)
        }

        val priorFinished = existing?.finished ?: false
        val id = existing?.id ?: Uuid.random().toString()
        val payload =
            PlaybackPositionSyncPayload(
                id = id,
                bookId = bookId,
                positionMs = positionMs,
                lastPlayedAt = lastPlayedAt,
                finished = finished,
                playbackSpeed = playbackSpeed,
                currentChapterId = currentChapterId,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )
        val result = upsert(payload, clientOpId = null, userId = userId)
        if (result !is AppResult.Success) return result

        // De-nested cascade (post-commit). Fire the finished flip when false → true; the caller is
        // responsible for detecting the flip condition. Each event routes through StatsRecorder,
        // which runs its own fixed ordering in its own transactions.
        if (finished && !priorFinished) {
            activeSessionRepo?.deleteForUserBook(userId, bookId)
            statsRecorder?.record(
                StatsEvent.BookCompleted(
                    userId = userId,
                    bookId = bookId,
                    occurredAt = Instant.fromEpochMilliseconds(lastPlayedAt),
                ),
            )
        } else if (!finished) {
            activeSessionRepo?.startOrRefresh(userId, bookId)
            if (existing == null) {
                statsRecorder?.record(
                    StatsEvent.BookRestarted(
                        userId = userId,
                        bookId = bookId,
                        occurredAt = Instant.fromEpochMilliseconds(startedBookOccurredAt ?: lastPlayedAt),
                        isReread = false,
                    ),
                )
            } else if (priorFinished) {
                statsRecorder?.record(
                    StatsEvent.BookRestarted(
                        userId = userId,
                        bookId = bookId,
                        occurredAt = Instant.fromEpochMilliseconds(startedBookOccurredAt ?: lastPlayedAt),
                        isReread = true,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Returns the current position for `(userId, bookId)`, or `null` if the user
     * has never played this book.
     */
    suspend fun getPosition(
        userId: String,
        bookId: String,
    ): PlaybackPositionSyncPayload? =
        suspendTransaction(db) {
            db.playbackPositionsQueries
                .selectLiveForUserBook(userId, bookId)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: PlaybackPositionId): String = idAsString(id)

    /**
     * All non-tombstoned positions for [userId], up to [limit] rows.
     * Order is unspecified — callers sort if needed.
     */
    suspend fun listForUser(
        userId: UserId,
        limit: Int,
    ): List<PlaybackPositionSyncPayload> =
        suspendTransaction(db) {
            db.playbackPositionsQueries
                .listForUser(userId.value, limit.toLong())
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Sparse batch lookup — returns only the positions that exist for the given [bookIds].
     * Missing positions (no progress recorded) are silently omitted. Returns an empty list
     * when [bookIds] is empty.
     */
    suspend fun findByBookIds(
        userId: UserId,
        bookIds: List<BookId>,
    ): List<PlaybackPositionSyncPayload> {
        if (bookIds.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            bookIds
                .map { it.value }
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.playbackPositionsQueries.findByBookIds(userId.value, chunk).executeAsList() }
                .map { it.toSyncPayload() }
        }
    }

    /**
     * Continue-listening semantics — books the user has started but not finished
     * (`isFinished = false`, `positionMs > 0`), ordered by `lastPlayedAt DESC`.
     * Matches the client Continue Listening shelf's filter.
     */
    suspend fun recentlyListenedForUser(
        userId: UserId,
        limit: Int,
    ): List<PlaybackPositionSyncPayload> =
        suspendTransaction(db) {
            db.playbackPositionsQueries
                .recentlyListenedForUser(userId.value, limit.toLong())
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Books the user finished (`isFinished = true`, not tombstoned), ordered by
     * `lastPlayedAt DESC` (most-recently-finished first).
     */
    suspend fun completedForUser(
        userId: UserId,
        limit: Int,
    ): List<PlaybackPositionSyncPayload> =
        suspendTransaction(db) {
            db.playbackPositionsQueries
                .completedForUser(userId.value, limit.toLong())
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** (userId, positionMs) for every user with an in-progress (unfinished, positionMs>0) position on [bookId]. */
    suspend fun listInProgressForBook(bookId: String): List<Pair<String, Long>> =
        suspendTransaction(db) {
            db.playbackPositionsQueries
                .listInProgressForBook(bookId) { userId, positionMs -> userId to positionMs }
                .executeAsList()
        }

    private fun Playback_positions.toSyncPayload(): PlaybackPositionSyncPayload =
        PlaybackPositionSyncPayload(
            id = id,
            bookId = book_id,
            positionMs = position_ms,
            lastPlayedAt = last_played_at,
            // `finished` is INTEGER 0/1 in SQLite; `playback_speed` is REAL (Double). Convert at the
            // boundary, the same place the Exposed bool/float adapters sat.
            finished = finished == 1L,
            playbackSpeed = playback_speed.toFloat(),
            currentChapterId = current_chapter_id,
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
