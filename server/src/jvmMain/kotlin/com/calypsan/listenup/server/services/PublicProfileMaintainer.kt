package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction as exposedSuspendTransaction

private val logger = KotlinLogging.logger {}

/** Days in the longest rolling window the projection tracks. */
private const val YEAR_WINDOW_DAYS = 365

/**
 * Rebuilds the global `public_profiles` projection from the authoritative `users`
 * and `user_stats` tables. Called whenever a user's stats change (via [UserStatsUpdater]),
 * their identity changes (via `ProfileServiceImpl`), or they are created/deleted.
 * Idempotent: [refresh] always rewrites the full row from source.
 *
 * **Engines.** Identity comes from the still-Exposed `users` table via [db] (a pure WAL SELECT,
 * safe under a concurrent SQLDelight writer). The aggregate fields and the 365-day window come
 * from `user_stats` / `listening_events` via [sql] (SQLDelight) — read on the same connection the
 * stats path holds open, so the just-written stats row is visible when [refresh] is invoked as a
 * hook. The projection write goes through [publicProfileRepo] (SQLDelight), nesting as a savepoint
 * inside any open transaction.
 */
class PublicProfileMaintainer(
    private val sql: ListenUpDatabase,
    private val db: Database,
    private val publicProfileRepo: PublicProfileRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Rebuild and upsert the projection row for [userId] from `users` + `user_stats`.
     * No-op if the user row is absent (e.g. mid-deletion). Stat fields default to 0
     * when the user has no `user_stats` row yet.
     */
    suspend fun refresh(userId: String) {
        // Identity from the Exposed `users` table (pure read).
        val identity =
            exposedSuspendTransaction(db) {
                UserTable
                    .selectAll()
                    .where { (UserTable.id eq userId) and UserTable.deletedAt.isNull() }
                    .firstOrNull()
                    ?.let {
                        UserIdentity(
                            displayName = it[UserTable.displayName],
                            avatarType = it[UserTable.avatarType],
                            tagline = it[UserTable.tagline],
                        )
                    }
            } ?: return

        val nowMs = clock.now().toEpochMilliseconds()

        // Aggregates from the SQLDelight `user_stats` / `listening_events` tables.
        val payload =
            suspendTransaction(sql) {
                val stats = sql.userStatsQueries.selectLiveForUser(userId).executeAsOneOrNull()
                val yearCutoff = nowMs - YEAR_WINDOW_DAYS * 86_400_000L
                val yearWindowSeconds =
                    sql.listeningEventsQueries
                        .sumWallSecondsSince(
                            userId,
                            yearCutoff,
                        ).executeAsOne()

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
            exposedSuspendTransaction(db) {
                UserTable
                    .selectAll()
                    .where { UserTable.deletedAt.isNull() }
                    .map { it[UserTable.id].value }
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
