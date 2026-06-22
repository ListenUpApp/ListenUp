package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Days in the longest rolling window the projection tracks. */
private const val YEAR_WINDOW_DAYS = 365

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
     */
    suspend fun refresh(userId: String) {
        // Identity from the `users` table (pure read; live rows only — a tombstoned/absent user
        // yields no row and the projection refresh no-ops, matching the prior Exposed read).
        val identity =
            suspendTransaction(sql) {
                sql.usersQueries.selectIdentityLiveById(id = userId).executeAsOneOrNull()
            }?.let {
                UserIdentity(
                    displayName = it.display_name,
                    avatarType = it.avatar_type,
                    tagline = it.tagline,
                )
            } ?: return

        val nowMs = clock.now().toEpochMilliseconds()
        // Home timezone for the windowed-streak day math (same frame the stats walk uses). Read
        // outside the payload transaction, mirroring UserStatsBackfillService.
        val tz = sql.homeTimeZone(userId)
        val cutoff7 = nowMs - 7 * 86_400_000L
        val cutoff30 = nowMs - 30 * 86_400_000L
        val yearCutoff = nowMs - YEAR_WINDOW_DAYS * 86_400_000L

        // Aggregates from the SQLDelight `user_stats` / `listening_events` / `book_reads` tables.
        val payload =
            suspendTransaction(sql) {
                val stats = sql.userStatsQueries.selectLiveForUser(userId).executeAsOneOrNull()
                val yearWindowSeconds =
                    sql.listeningEventsQueries.sumWallSecondsSince(userId, yearCutoff).executeAsOne()

                // Windowed books: distinct books finished within each trailing window.
                val books = sql.bookReadsQueries

                fun booksFinishedSince(cutoffMs: Long): Int =
                    books.countDistinctFinishedSince(userId, cutoffMs).executeAsOne().toInt()

                // Windowed streak: longest consecutive listening-day run whose events fall in the window.
                val events = sql.listeningEventsQueries

                fun longestStreakSince(cutoffMs: Long): Int =
                    longestStreakInWindow(events.selectEndedAtForUserSince(userId, cutoffMs).executeAsList(), tz)

                PublicProfileSyncPayload(
                    id = userId,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    tagline = identity.tagline,
                    totalSecondsAllTime = stats?.total_seconds_all_time ?: 0L,
                    totalSecondsLast7Days = stats?.total_seconds_last_7_days ?: 0L,
                    totalSecondsLast30Days = stats?.total_seconds_last_30_days ?: 0L,
                    totalSecondsLast365Days = yearWindowSeconds,
                    booksFinished = (stats?.books_finished ?: 0L).toInt(),
                    currentStreakDays = (stats?.current_streak_days ?: 0L).toInt(),
                    longestStreakDays = (stats?.longest_streak_days ?: 0L).toInt(),
                    booksFinishedLast7Days = booksFinishedSince(cutoff7),
                    booksFinishedLast30Days = booksFinishedSince(cutoff30),
                    booksFinishedLast365Days = booksFinishedSince(yearCutoff),
                    longestStreakLast7Days = longestStreakSince(cutoff7),
                    longestStreakLast30Days = longestStreakSince(cutoff30),
                    longestStreakLast365Days = longestStreakSince(yearCutoff),
                    revision = 0,
                    updatedAt = 0,
                    createdAt = 0,
                    deletedAt = null,
                )
            }

        publicProfileRepo.upsert(payload, clientOpId = null, userId = null)
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
            .onFailure {
                logger.warn(
                    it,
                ) { "public_profiles refresh failed for $userId; projection will self-heal on next backfill" }
            }
    }

    /** Best-effort [tombstone]; see [refreshBestEffort]. */
    suspend fun tombstoneBestEffort(userId: String) {
        runCatchingCancellable { tombstone(userId) }
            .onFailure {
                logger.warn(
                    it,
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
    )
}
