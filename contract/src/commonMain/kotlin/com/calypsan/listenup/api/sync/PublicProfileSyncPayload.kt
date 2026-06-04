package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for one user's **public** social profile — the book-agnostic, globally
 * shared facet that powers the Discover leaderboard and social roster.
 *
 * Unlike [UserStatsSyncPayload] (private, own-rows-only) this domain is **global**:
 * every client's catch-up returns every user's row. It is a server-maintained
 * projection of the `users` + `user_stats` tables, rebuilt by `PublicProfileMaintainer`
 * whenever a user's stats or identity change. It carries only aggregates and identity —
 * no per-book data — so it can never leak book access.
 *
 * `id` equals the owning user id (1:1 with the user). The rolling-window second totals
 * ([totalSecondsLast7Days] / [totalSecondsLast30Days] / [totalSecondsLast365Days]) are
 * anchored at the present, mirroring `UserStatsUpdater`'s window math.
 *
 * Implements [Tombstoned]: a deleted user's row is soft-deleted so clients prune it.
 */
@Serializable
@SerialName("PublicProfileSyncPayload")
data class PublicProfileSyncPayload(
    val id: String,
    val displayName: String,
    val avatarType: String,
    val totalSecondsAllTime: Long,
    val totalSecondsLast7Days: Long,
    val totalSecondsLast30Days: Long,
    val totalSecondsLast365Days: Long,
    val booksFinished: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : Tombstoned
