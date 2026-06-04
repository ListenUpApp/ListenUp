package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.server.db.ListeningEventTable
import com.calypsan.listenup.server.db.UserStatsTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.sync.PublicProfileRepository
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** Days in the longest rolling window the projection tracks. */
private const val YEAR_WINDOW_DAYS = 365

/**
 * Rebuilds the global `public_profiles` projection from the authoritative `users`
 * and `user_stats` tables. Called whenever a user's stats change (via
 * [UserStatsUpdater]), their identity changes (via `ProfileServiceImpl`), or they
 * are created/deleted. Idempotent: [refresh] always rewrites the full row from source.
 */
class PublicProfileMaintainer(
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
        val payload =
            suspendTransaction(db) {
                val userRow =
                    UserTable
                        .selectAll()
                        .where { (UserTable.id eq userId) and UserTable.deletedAt.isNull() }
                        .firstOrNull() ?: return@suspendTransaction null

                val statsRow =
                    UserStatsTable
                        .selectAll()
                        .where { UserStatsTable.id eq userId }
                        .firstOrNull()

                val nowMs = clock.now().toEpochMilliseconds()
                val last365 = sumWindowSeconds(userId, days = YEAR_WINDOW_DAYS, asOfMs = nowMs)

                PublicProfileSyncPayload(
                    id = userId,
                    displayName = userRow[UserTable.displayName],
                    avatarType = userRow[UserTable.avatarType],
                    totalSecondsAllTime = statsRow?.get(UserStatsTable.totalSecondsAllTime) ?: 0L,
                    totalSecondsLast7Days = statsRow?.get(UserStatsTable.totalSecondsLast7Days) ?: 0L,
                    totalSecondsLast30Days = statsRow?.get(UserStatsTable.totalSecondsLast30Days) ?: 0L,
                    totalSecondsLast365Days = last365,
                    booksFinished = statsRow?.get(UserStatsTable.booksFinished) ?: 0,
                    currentStreakDays = statsRow?.get(UserStatsTable.currentStreakDays) ?: 0,
                    longestStreakDays = statsRow?.get(UserStatsTable.longestStreakDays) ?: 0,
                    revision = 0,
                    updatedAt = 0,
                    createdAt = 0,
                    deletedAt = null,
                )
            } ?: return

        publicProfileRepo.upsert(payload, clientOpId = null, userId = null)
    }

    /** Soft-delete the projection row for a removed user, so clients prune it. */
    suspend fun tombstone(userId: String) {
        publicProfileRepo.softDelete(userId, clientOpId = null, userId = null)
    }

    /**
     * One-time backfill: refresh the projection for every non-deleted user. Idempotent;
     * invoked at startup after migrations to populate the table for pre-existing users.
     */
    suspend fun backfillAll() {
        val userIds =
            suspendTransaction(db) {
                UserTable
                    .selectAll()
                    .where { UserTable.deletedAt.isNull() }
                    .map { it[UserTable.id].value }
            }
        userIds.forEach { refresh(it) }
    }

    /**
     * Indexed SUM of listening seconds in the last [days] days, anchored at [asOfMs].
     * Must be called inside an open Exposed transaction — it issues DSL queries
     * directly without opening a nested transaction.
     */
    private fun sumWindowSeconds(
        userId: String,
        days: Int,
        asOfMs: Long,
    ): Long {
        val cutoffMs = asOfMs - days * 86_400_000L
        return ListeningEventTable
            .selectAll()
            .where {
                (ListeningEventTable.userId eq userId) and
                    (ListeningEventTable.endedAt greaterEq cutoffMs)
            }.sumOf { row ->
                (row[ListeningEventTable.endedAt] - row[ListeningEventTable.startedAt]) / 1_000L
            }
    }
}
