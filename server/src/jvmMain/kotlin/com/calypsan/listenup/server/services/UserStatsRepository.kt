package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.UserStatsId
import com.calypsan.listenup.server.db.UserStatsTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for per-user materialized listening stats (Playback P2).
 *
 * One row per user (`id == userId`) — the current summary of a user's entire
 * listening history. Updated in place on every stats recomputation; rolling-window
 * columns (`totalSecondsLast7Days` / `totalSecondsLast30Days`) are lazily
 * refreshed on catch-up.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * call routes through the per-user dimension of the substrate.
 *
 * `idAsString(UserStatsId) = id.value` is load-bearing — Kotlin's default
 * `toString()` on a value class returns `"UserStatsId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * `userStatsUpdaterProvider` is a lazy provider rather than a direct reference to
 * break what would otherwise be a construction-time circular dependency:
 * `UserStatsUpdater` depends on `UserStatsRepository` (to write recomputed rows),
 * so the provider is invoked only on first use inside [pullSince] — by which time
 * the Koin container has fully resolved both singletons.
 */
class UserStatsRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    private val userStatsUpdaterProvider: () -> UserStatsUpdater? = { null },
) : SyncableRepository<UserStatsSyncPayload, UserStatsId>(
        db = db,
        table = UserStatsTable,
        bus = bus,
        registry = registry,
        domainName = "user_stats",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<UserStatsSyncPayload> =
        UserStatsSyncPayload.serializer()

    override fun idAsString(id: UserStatsId): String = id.value

    override val UserStatsSyncPayload.id: UserStatsId
        get() = UserStatsId(this.id)

    override fun UserStatsSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): UserStatsSyncPayload? =
        UserStatsTable
            .selectAll()
            .where { UserStatsTable.id eq idStr }
            .firstOrNull()
            ?.toUserStatsPayload()

    override suspend fun writePayload(
        value: UserStatsSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "UserStatsRepository.writePayload requires a userId" }
        if (existed) {
            UserStatsTable.update({ UserStatsTable.id eq value.id }) { stmt ->
                stmt[UserStatsTable.totalSecondsAllTime] = value.totalSecondsAllTime
                stmt[UserStatsTable.totalSecondsLast7Days] = value.totalSecondsLast7Days
                stmt[UserStatsTable.totalSecondsLast30Days] = value.totalSecondsLast30Days
                stmt[UserStatsTable.booksStarted] = value.booksStarted
                stmt[UserStatsTable.booksFinished] = value.booksFinished
                stmt[UserStatsTable.currentStreakDays] = value.currentStreakDays
                stmt[UserStatsTable.longestStreakDays] = value.longestStreakDays
                stmt[UserStatsTable.lastEventDate] = value.lastEventDate
                stmt[UserStatsTable.revision] = rev
                stmt[UserStatsTable.updatedAt] = now
                stmt[UserStatsTable.deletedAt] = null
                stmt[UserStatsTable.clientOpId] = clientOpId
            }
        } else {
            UserStatsTable.insert { stmt ->
                stmt[UserStatsTable.id] = value.id
                stmt[UserStatsTable.userId] = userId
                stmt[UserStatsTable.totalSecondsAllTime] = value.totalSecondsAllTime
                stmt[UserStatsTable.totalSecondsLast7Days] = value.totalSecondsLast7Days
                stmt[UserStatsTable.totalSecondsLast30Days] = value.totalSecondsLast30Days
                stmt[UserStatsTable.booksStarted] = value.booksStarted
                stmt[UserStatsTable.booksFinished] = value.booksFinished
                stmt[UserStatsTable.currentStreakDays] = value.currentStreakDays
                stmt[UserStatsTable.longestStreakDays] = value.longestStreakDays
                stmt[UserStatsTable.lastEventDate] = value.lastEventDate
                stmt[UserStatsTable.revision] = rev
                stmt[UserStatsTable.createdAt] = now
                stmt[UserStatsTable.updatedAt] = now
                stmt[UserStatsTable.deletedAt] = null
                stmt[UserStatsTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the materialized stats row for [userId], or `null` if the user has
     * no listening history yet.
     */
    suspend fun getForUser(userId: String): UserStatsSyncPayload? =
        suspendTransaction(db) {
            UserStatsTable
                .selectAll()
                .where {
                    (UserStatsTable.userId eq userId) and
                        (UserStatsTable.deletedAt eq null)
                }.firstOrNull()
                ?.toUserStatsPayload()
        }

    /**
     * Overrides [SyncableRepository.pullSince] to lazily refresh the rolling-window fields
     * (`totalSecondsLast7Days` / `totalSecondsLast30Days`) when the stats row is stale
     * (older than [STATS_STALENESS_LIMIT_MS]).
     *
     * A user who listens actively keeps their stats fresh via [UserStatsUpdater.onListeningEvent].
     * An idle user's windows drift stale as time passes — an event from 6 days ago is
     * inside the 7-day window today but outside it in 2 days. This catch-up path ensures
     * the row is corrected before it is returned to a syncing client.
     *
     * Only fires for single-user queries (`userId != null`) — global catch-up paging is
     * unaffected.
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<UserStatsSyncPayload> {
        if (userId != null) refreshWindowsIfStale(userId)
        return super.pullSince(userId, cursor, limit, extraWhere)
    }

    /**
     * Recomputes [userId]'s rolling windows when the stats row is older than
     * [STATS_STALENESS_LIMIT_MS] — the lazy correction path described on [pullSince].
     * A no-op when no updater is wired or the user has no stats row yet.
     */
    private suspend fun refreshWindowsIfStale(userId: String) {
        val updater = userStatsUpdaterProvider() ?: return
        val existing = getForUser(userId) ?: return
        val now = clock.now().toEpochMilliseconds()
        if (now - existing.updatedAt > STATS_STALENESS_LIMIT_MS) {
            updater.recomputeWindowsOnly(userId, asOfMs = now)
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: UserStatsId): String = idAsString(id)

    private companion object {
        /** Threshold after which a stats row is considered stale and windows are recomputed. */
        private const val STATS_STALENESS_LIMIT_MS = 60 * 60 * 1000L // 1 hour
    }
}

private fun ResultRow.toUserStatsPayload(): UserStatsSyncPayload =
    UserStatsSyncPayload(
        id = this[UserStatsTable.id],
        totalSecondsAllTime = this[UserStatsTable.totalSecondsAllTime],
        totalSecondsLast7Days = this[UserStatsTable.totalSecondsLast7Days],
        totalSecondsLast30Days = this[UserStatsTable.totalSecondsLast30Days],
        booksStarted = this[UserStatsTable.booksStarted],
        booksFinished = this[UserStatsTable.booksFinished],
        currentStreakDays = this[UserStatsTable.currentStreakDays],
        longestStreakDays = this[UserStatsTable.longestStreakDays],
        lastEventDate = this[UserStatsTable.lastEventDate],
        revision = this[UserStatsTable.revision],
        updatedAt = this[UserStatsTable.updatedAt],
        createdAt = this[UserStatsTable.createdAt],
        deletedAt = this[UserStatsTable.deletedAt],
    )
