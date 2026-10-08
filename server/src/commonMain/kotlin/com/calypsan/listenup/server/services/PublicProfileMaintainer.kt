package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.domain.stats.StatsWindow
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlinx.datetime.TimeZone

private val logger = loggerFor<PublicProfileMaintainer>()

/**
 * Rebuilds the global `public_profiles` projection from the authoritative `users`
 * and `user_stats` tables. Called whenever a user's stats change (via [UserStatsUpdater]),
 * their identity changes (via `ProfileServiceImpl`), or they are created/deleted.
 * Idempotent: [refresh] always rewrites the full row from source.
 *
 * **Engines.** Everything now reads through [sql] (SQLDelight). Identity comes from the `users`
 * table; the aggregate fields and the 365-day window come from `user_stats` / `listening_events` —
 * read on the same connection the stats path holds open, so the just-written stats row is visible
 * when [refresh] is invoked as a hook. The projection write goes through [publicProfileRepo]
 * (SQLDelight), nesting as a savepoint inside any open transaction.
 */
class PublicProfileMaintainer(
    private val sql: ListenUpDatabase,
    private val publicProfileRepo: PublicProfileRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Rebuild and upsert the projection row for [userId] from `users` + `user_stats`.
     * No-op if the user row is absent (e.g. mid-deletion). Stat fields default to 0
     * when the user has no `user_stats` row yet.
     *
     * @param tz a caller-provided memo of `sql.homeTimeZone(userId)` — never a different zone.
     * Pass `null` (default) to have this function read it itself; callers that already read the
     * home timezone for another step of the same cascade (e.g. [StatsRecorder]) should pass it
     * through to avoid a second read of the same row.
     */
    suspend fun refresh(
        userId: String,
        tz: TimeZone? = null,
    ) {
        val payload = buildPayload(userId, tz) ?: return
        publicProfileRepo.upsert(payload, clientOpId = null, userId = null)
    }

    /**
     * [refresh] only when the rebuilt row differs from the stored one, so a periodic re-check (the
     * midnight rollover sweep) doesn't bump the revision — and push a frame to every client — for a
     * user whose leaderboard row hasn't moved. Returns `true` when the row was written.
     *
     * The windowed books and streak columns live only in this projection, not in `user_stats`, so a
     * week rolling over can change this row while `user_stats` stays put; this is what catches that.
     */
    suspend fun refreshIfChanged(
        userId: String,
        tz: TimeZone? = null,
    ): Boolean {
        val payload = buildPayload(userId, tz) ?: return false
        val stored = publicProfileRepo.getById(userId)
        if (stored != null && stored.copy(revision = 0, updatedAt = 0, createdAt = 0) == payload) return false
        publicProfileRepo.upsert(payload, clientOpId = null, userId = null)
        return true
    }

    /**
     * Rebuild [userId]'s projection row from `users` + `user_stats` + the windowed reads, or `null` when
     * the user row is absent. `revision` and the timestamps are left at 0 for the substrate to assign.
     */
    private suspend fun buildPayload(
        userId: String,
        tz: TimeZone?,
    ): PublicProfileSyncPayload? {
        // Identity from the `users` table (pure read; live rows only — a tombstoned/absent user
        // yields no row and the projection refresh no-ops, matching the prior Exposed read).
        val identity =
            suspendTransaction(sql) {
                sql.usersQueries.selectIdentityLiveById(id = userId).executeAsOneOrNull()
            }?.let { row ->
                UserIdentity(
                    displayName = row.display_name,
                    avatarType = row.avatar_type,
                    tagline = row.tagline,
                    avatarUpdatedAt = row.avatar_updated_at,
                )
            } ?: return null

        val now = clock.now()
        // Home timezone for the window starts and the windowed-streak day math (same frame the stats
        // walk uses). Read outside the payload transaction, mirroring UserStatsBackfillService.
        val tzResolved = tz ?: sql.homeTimeZone(userId)
        val weekStart = StatsWindow.Week.startMs(now, tzResolved)
        val monthStart = StatsWindow.Month.startMs(now, tzResolved)
        val yearStart = StatsWindow.Year.startMs(now, tzResolved)

        // Aggregates from the SQLDelight `user_stats` / `listening_events` / `book_reads` tables.
        return suspendTransaction(sql) {
            val stats = sql.userStatsQueries.selectLiveForUser(userId).executeAsOneOrNull()
            val yearWindowSeconds =
                sql.listeningEventsQueries
                    .sumWallSecondsEndedSince(
                        userId = userId,
                        windowStartMs = yearStart,
                    ).executeAsOne()

            // Windowed books: distinct books finished within each calendar window.
            val books = sql.bookReadsQueries

            fun booksFinishedSince(cutoffMs: Long): Int =
                books.countDistinctFinishedSince(userId, cutoffMs).executeAsOne().toInt()

            // Windowed streak: longest consecutive listening-day run whose events fall in the window.
            val events = sql.listeningEventsQueries

            fun longestStreakSince(cutoffMs: Long): Int =
                longestStreakInWindow(
                    events.selectEndedAtForUserSince(userId, cutoffMs).executeAsList(),
                    tzResolved,
                )

            PublicProfileSyncPayload(
                id = userId,
                displayName = identity.displayName,
                avatarType = identity.avatarType,
                tagline = identity.tagline,
                avatarUpdatedAt = identity.avatarUpdatedAt,
                totalSecondsAllTime = stats?.total_seconds_all_time ?: 0L,
                totalSecondsLast7Days = stats?.total_seconds_last_7_days ?: 0L,
                totalSecondsLast30Days = stats?.total_seconds_last_30_days ?: 0L,
                totalSecondsLast365Days = yearWindowSeconds,
                booksFinished = (stats?.books_finished ?: 0L).toInt(),
                currentStreakDays = (stats?.current_streak_days ?: 0L).toInt(),
                longestStreakDays = (stats?.longest_streak_days ?: 0L).toInt(),
                booksFinishedLast7Days = booksFinishedSince(weekStart),
                booksFinishedLast30Days = booksFinishedSince(monthStart),
                booksFinishedLast365Days = booksFinishedSince(yearStart),
                longestStreakLast7Days = longestStreakSince(weekStart),
                longestStreakLast30Days = longestStreakSince(monthStart),
                longestStreakLast365Days = longestStreakSince(yearStart),
                revision = 0,
                updatedAt = 0,
                createdAt = 0,
                deletedAt = null,
            )
        }
    }

    /** Soft-delete the projection row for a removed user, so clients prune it. */
    suspend fun tombstone(userId: String) {
        publicProfileRepo.softDelete(userId, clientOpId = null, userId = null)
    }

    /**
     * Best-effort [refresh]: the public_profiles projection is a derived view that
     * self-heals via [backfillAll] at startup, so a refresh failure must never fail
     * the user-facing operation that triggered it. Logs and swallows everything except
     * [CancellationException]. Use from user-lifecycle call sites (NOT the stats path,
     * where the projection write is intentionally atomic with the stats write).
     */
    suspend fun refreshBestEffort(userId: String) {
        runCatchingCancellable { refresh(userId) }
            .onFailure { failure ->
                logger.warn(
                    failure,
                ) { "public_profiles refresh failed for $userId; projection will self-heal on next backfill" }
            }
    }

    /** Best-effort [refreshIfChanged]; see [refreshBestEffort]. Returns `false` when the refresh failed. */
    suspend fun refreshIfChangedBestEffort(userId: String): Boolean =
        runCatchingCancellable { refreshIfChanged(userId) }
            .onFailure { failure ->
                logger.warn(
                    failure,
                ) { "public_profiles refresh failed for $userId; projection will self-heal on next backfill" }
            }.getOrDefault(false)

    /** Best-effort [tombstone]; see [refreshBestEffort]. */
    suspend fun tombstoneBestEffort(userId: String) {
        runCatchingCancellable { tombstone(userId) }
            .onFailure { failure ->
                logger.warn(
                    failure,
                ) { "public_profiles tombstone failed for $userId; projection will self-heal on next backfill" }
            }
    }

    /**
     * One-time backfill: refresh the projection for every non-deleted user. Idempotent;
     * invoked at startup after migrations to populate the table for pre-existing users.
     *
     * Cost is O(users) transactions (one [refresh] each, including a window SUM); fine for
     * the self-hosted small-userbase scale this app targets — not designed for large fleets.
     */
    suspend fun backfillAll() {
        val userIds =
            suspendTransaction(sql) {
                sql.usersQueries.selectLiveUserIds().executeAsList()
            }
        userIds.forEach { refresh(it) }
    }

    /** A user's display identity from the `users` table. */
    private data class UserIdentity(
        val displayName: String,
        val avatarType: String,
        val tagline: String?,
        val avatarUpdatedAt: Long,
    )
}
