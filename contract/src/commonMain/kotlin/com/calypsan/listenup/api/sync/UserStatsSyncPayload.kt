package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for one user's materialized listening stats — the lean fixed-shape
 * row that powers stats screens with a single row read. Maintained server-side
 * by `UserStatsUpdater` as listening events arrive and as playback positions
 * flip `finished`. The window totals (`totalSecondsLast7Days` /
 * `totalSecondsLast30Days`) cover the shared
 * [com.calypsan.listenup.domain.stats.StatsWindow]s in the user's home timezone and
 * roll over at local midnight via the server's freshness sweep.
 *
 * `id` equals the owning user id (1:1 with the user). `lastEventDate` is
 * `"YYYY-MM-DD"` in the user's TZ — drives streak math; null until the user's
 * first event.
 *
 * Implements [Tombstoned] for substrate-shape uniformity; tombstones are not
 * emitted in P2 (the row exists for the lifetime of the user).
 */
@Serializable
@SerialName("UserStatsSyncPayload")
data class UserStatsSyncPayload(
    override val id: String,
    val totalSecondsAllTime: Long,
    val totalSecondsLast7Days: Long,
    val totalSecondsLast30Days: Long,
    val booksStarted: Int,
    val booksFinished: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val lastEventDate: String?,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
