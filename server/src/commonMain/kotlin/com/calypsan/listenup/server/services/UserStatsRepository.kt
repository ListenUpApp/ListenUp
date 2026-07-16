package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.UserStatsId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.User_stats
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for per-user materialized listening stats (Playback P2).
 *
 * One row per user (`id == userId`) — the current summary of a user's entire listening
 * history. Updated in place on every stats recomputation; rolling-window columns
 * (`totalSecondsLast7Days` / `totalSecondsLast30Days`) are lazily refreshed on catch-up.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest` call routes
 * through the per-user dimension of the [SqlSyncableRepository] base (the [ShelfRepository]
 * pattern): the owning `user_id` is stamped on insert, and pull/digest filter to the
 * authenticated user via the substrate's `*ForUser` variants.
 *
 * `idAsString(UserStatsId) = id.value` is load-bearing — Kotlin's default `toString()` on a
 * value class returns `"UserStatsId(value=foo)"`, which would corrupt every column the id is
 * written to.
 *
 * `userStatsUpdaterProvider` is a lazy provider rather than a direct reference to break what
 * would otherwise be a construction-time circular dependency: [UserStatsUpdater] depends on
 * [UserStatsRepository] (to write recomputed rows), so the provider is invoked only on first use
 * inside [pullSince] — by which time the Koin container has fully resolved both singletons.
 */
class UserStatsRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    private val userStatsUpdaterProvider: () -> UserStatsUpdater? = { null },
) : SqlSyncableRepository<UserStatsSyncPayload, UserStatsId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.USER_STATS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override fun idAsString(id: UserStatsId): String = id.value

    override val UserStatsSyncPayload.id: UserStatsId
        get() = UserStatsId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.userStatsQueries].
     * The canonical user-scoped adapter shape (see [ShelfRepository]): the four global methods
     * forward to the unfiltered queries; the two `*ForUser` methods forward to the user-filtered
     * queries the base calls when [userScoped] is true.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.userStatsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.userStatsQueries
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
                db.userStatsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.userStatsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.userStatsQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.userStatsQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): UserStatsSyncPayload? =
        db.userStatsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toUserStatsPayload()

    override fun readPayloads(idStrs: List<String>): List<UserStatsSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.userStatsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toUserStatsPayload() }
    }

    override fun writePayload(
        value: UserStatsSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "UserStatsRepository.writePayload requires a userId" }
        if (existed) {
            db.userStatsQueries.update(
                total_seconds_all_time = value.totalSecondsAllTime,
                total_seconds_last_7_days = value.totalSecondsLast7Days,
                total_seconds_last_30_days = value.totalSecondsLast30Days,
                books_started = value.booksStarted.toLong(),
                books_finished = value.booksFinished.toLong(),
                current_streak_days = value.currentStreakDays.toLong(),
                longest_streak_days = value.longestStreakDays.toLong(),
                last_event_date = value.lastEventDate,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.userStatsQueries.insert(
                id = value.id,
                user_id = userId,
                total_seconds_all_time = value.totalSecondsAllTime,
                total_seconds_last_7_days = value.totalSecondsLast7Days,
                total_seconds_last_30_days = value.totalSecondsLast30Days,
                books_started = value.booksStarted.toLong(),
                books_finished = value.booksFinished.toLong(),
                current_streak_days = value.currentStreakDays.toLong(),
                longest_streak_days = value.longestStreakDays.toLong(),
                last_event_date = value.lastEventDate,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Returns the materialized stats row for [userId], or `null` if the user has
     * no listening history yet.
     */
    suspend fun getForUser(userId: String): UserStatsSyncPayload? =
        suspendTransaction(db) {
            db.userStatsQueries
                .selectLiveForUser(userId)
                .executeAsOneOrNull()
                ?.toUserStatsPayload()
        }

    /**
     * Overrides [SqlSyncableRepository.pullSince] to lazily refresh the rolling-window fields
     * (`totalSecondsLast7Days` / `totalSecondsLast30Days`) when the stats row is stale
     * (older than [STATS_STALENESS_LIMIT_MS]).
     *
     * A user who listens actively keeps their stats fresh via the [StatsRecorder] write cascade.
     * An idle user's windows drift stale as time passes — an event from 6 days ago is inside the
     * 7-day window today but outside it in 2 days. This catch-up path ensures the row is corrected
     * before it is returned to a syncing client.
     *
     * Only fires for single-user queries (`userId != null`) — global catch-up paging is unaffected.
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<UserStatsSyncPayload> {
        if (userId != null) healStatsIfStale(userId)
        return super.pullSince(userId, cursor, limit, extraWhere)
    }

    /**
     * Re-derives [userId]'s stats (rolling windows AND current streak) and refreshes the projection
     * when the stats row is older than [STATS_STALENESS_LIMIT_MS] — the lazy correction path described
     * on [pullSince]. A no-op when no updater is wired, the user has no stats row yet, or nothing drifted.
     */
    private suspend fun healStatsIfStale(userId: String) {
        val updater = userStatsUpdaterProvider() ?: return
        val existing = getForUser(userId) ?: return
        val now = clock.now().toEpochMilliseconds()
        if (now - existing.updatedAt > STATS_STALENESS_LIMIT_MS) {
            updater.healStaleStats(userId, asOfMs = now)
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: UserStatsId): String = idAsString(id)

    private companion object {
        /** Threshold after which a stats row is considered stale and windows are recomputed. */
        private const val STATS_STALENESS_LIMIT_MS = 60 * 60 * 1000L // 1 hour

        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}

private fun User_stats.toUserStatsPayload(): UserStatsSyncPayload =
    UserStatsSyncPayload(
        id = id,
        totalSecondsAllTime = total_seconds_all_time,
        totalSecondsLast7Days = total_seconds_last_7_days,
        totalSecondsLast30Days = total_seconds_last_30_days,
        booksStarted = books_started.toInt(),
        booksFinished = books_finished.toInt(),
        currentStreakDays = current_streak_days.toInt(),
        longestStreakDays = longest_streak_days.toInt(),
        lastEventDate = last_event_date,
        revision = revision,
        updatedAt = updated_at,
        createdAt = created_at,
        deletedAt = deleted_at,
    )
