package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import kotlin.time.Duration.Companion.seconds

/**
 * The ranking to show for [category] — a pick from an already-loaded snapshot, never a new query.
 *
 * All three lists arrive together, which is what makes switching tabs free. Shared so the browser
 * and the Compose clients cannot disagree about which list a tab means.
 */
fun leaderboardEntries(
    snapshot: LeaderboardSnapshot,
    category: LeaderboardCategory,
): List<LeaderboardEntry> =
    when (category) {
        LeaderboardCategory.Time -> snapshot.time
        LeaderboardCategory.Books -> snapshot.books
        LeaderboardCategory.Streak -> snapshot.streak
    }

/**
 * The stat shown beside a name, in the units the current tab ranks by.
 *
 * Note the streak tab reads [LeaderboardEntry.longestStreakDays], not `currentStreakDays`: the
 * board ranks the best run someone has managed, not whatever run they happen to be on today. That
 * is the kind of detail a second implementation gets wrong, which is why there is only one.
 */
fun leaderboardLabel(
    entry: LeaderboardEntry,
    category: LeaderboardCategory,
): String =
    when (category) {
        // Identical to the hand-rolled formatter this replaced ("2h 5m" / "45m" / "2h" / "0m"),
        // so the board reads exactly as before while losing a duplicate of the shared vocabulary.
        LeaderboardCategory.Time -> DurationFormatter.hoursMinutesCompact(entry.totalSeconds.seconds)

        LeaderboardCategory.Books -> plural(entry.booksFinished, "book")

        LeaderboardCategory.Streak -> plural(entry.longestStreakDays, "day")
    }
