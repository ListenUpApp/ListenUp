package com.calypsan.listenup.client.presentation.home

/**
 * What a stats card says about streaks — one rule for every platform (spec §2.6). A live streak shows as a header chip
 * with the best in the footer; a lapsed one shows only the best; none shows nothing. Never a "0-day streak".
 *
 * @property chipDays the current streak for the header chip, or null when there is none.
 * @property bestDays the longest streak for the footer, or null when there has never been one.
 */
data class StreakDisplay(
    val chipDays: Int?,
    val bestDays: Int?,
)

/** The [StreakDisplay] for a [current] and [best] streak, in days. Pure. */
fun streakDisplay(
    current: Int,
    best: Int,
): StreakDisplay =
    StreakDisplay(
        chipDays = current.takeIf { it > 0 },
        bestDays = maxOf(best, current).takeIf { it > 0 },
    )
