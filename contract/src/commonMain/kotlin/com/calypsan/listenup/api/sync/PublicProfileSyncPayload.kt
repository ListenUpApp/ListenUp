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
    override val id: String,
    val displayName: String,
    val avatarType: String,
    val tagline: String?,
    val totalSecondsAllTime: Long,
    val totalSecondsLast7Days: Long,
    val totalSecondsLast30Days: Long,
    val totalSecondsLast365Days: Long,
    val booksFinished: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    // Windowed leaderboard metrics, computed live by PublicProfileMaintainer.refresh() and anchored
    // at the present (like the rolling-window seconds above). Books = distinct books finished in the
    // window; streak = the longest consecutive listening-day run whose days fall inside the window
    // (so it caps at the window length). Default 0 so pre-V45 rows and non-projection callers omit them.
    val booksFinishedLast7Days: Int = 0,
    val booksFinishedLast30Days: Int = 0,
    val booksFinishedLast365Days: Int = 0,
    val longestStreakLast7Days: Int = 0,
    val longestStreakLast30Days: Int = 0,
    val longestStreakLast365Days: Int = 0,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
    /** Epoch-ms the avatar bytes last changed server-side; the avatar re-download signal + Coil cache-buster. 0 = never. */
    val avatarUpdatedAt: Long = 0,
) : SyncPayload
