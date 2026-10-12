package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod

/**
 * What the leaderboard is showing: which period, and which ranking within it.
 *
 * Its own state rather than a field of the board's data, so the period chips and the category
 * control render, with the right one selected, while the board is loading, empty or failed.
 *
 * @property period The selected window (Week · Month · Year · All time).
 * @property category The selected ranking (Time · Books · Streak).
 */
data class LeaderboardSelection(
    val period: LeaderboardPeriod,
    val category: LeaderboardCategory,
)
