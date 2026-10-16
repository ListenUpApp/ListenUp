package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.activityParts
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import com.calypsan.listenup.client.presentation.discover.leaderboardLabel
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.EmptyState
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/*
 * Leaderboard and Activity: the community record. On the web desktop they sit in their own aside
 * beside the main column (spec §3.7).
 */

/**
 * The leaderboard: a period, a category, and a ranking.
 *
 * Both controls are cheap — all three categories arrive in one snapshot, so switching a tab is a
 * list pick rather than a query. The period genuinely re-queries, which is why it reads as the
 * outer control.
 *
 * The controls render in every state, from [selection], as on Android and iOS: a period nobody
 * listened in is Empty, and the way out of it is another period.
 */
@Composable
internal fun LeaderboardSection(
    state: LeaderboardUiState,
    selection: LeaderboardSelection,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Section("Leaderboard") {
        Div(attrs = { classes("disc-lb-controls") }) {
            Div(attrs = { classes("disc-chips") }) {
                PERIODS.forEach { tab ->
                    Chip(
                        tab.label,
                        selected = tab.period == selection.period,
                        accessibleLabel = tab.accessibleLabel,
                    ) {
                        onSelectPeriod(tab.period)
                    }
                }
            }
            Div(attrs = { classes("disc-chips") }) {
                LeaderboardCategory.entries.forEach { category ->
                    Chip(categoryLabel(category), selected = category == selection.category) {
                        onSelectCategory(category)
                    }
                }
            }
        }

        when (state) {
            is LeaderboardUiState.Loading -> {
                SectionSkeleton()
            }

            is LeaderboardUiState.Empty -> {
                EmptyState(title = "No listening recorded yet", body = "The board fills in as people listen.")
            }

            is LeaderboardUiState.Error -> {
                // Every section's failure offers the same way back: Try again, which always works.
                SectionError("The leaderboard could not be loaded.", onRetry = onRefresh)
            }

            is LeaderboardUiState.Data -> {
                val entries = leaderboardEntries(state.snapshot, selection.category)
                if (entries.isEmpty()) {
                    EmptyState(title = "Nothing in this category yet", body = "Try another period.")
                } else {
                    Div(attrs = { classes("disc-lb") }) {
                        entries.forEach { entry ->
                            key(entry.userId) {
                                Div(attrs = { classes("disc-lb-row") }) {
                                    Span(attrs = { classes("disc-lb-rank") }) { Text("${entry.rank}") }
                                    PersonLink(entry.displayName, entry.userId, onOpenProfile, "disc-lb-name")
                                    Span(attrs = { classes("disc-lb-stat", "mono") }) {
                                        Text(leaderboardLabel(entry, selection.category))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The feed: who did what, most recent first. */
@Composable
internal fun ActivityFeedSection(
    state: ActivityFeedUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Section("Recent activity") {
        when (state) {
            is ActivityFeedUiState.Loading -> {
                SectionSkeleton()
            }

            is ActivityFeedUiState.Error -> {
                SectionError(state.error.message, onRetry = onRefresh)
            }

            is ActivityFeedUiState.Ready -> {
                if (state.isEmpty) {
                    EmptyState(
                        title = "Nothing has happened yet",
                        body = "Activity from everyone on this server shows up here.",
                    )
                } else {
                    Div(attrs = { classes("disc-feed") }) {
                        state.activities.forEach { item ->
                            key(item.id) {
                                ActivityRow(item, nowMs, onOpenBook, onOpenProfile)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One activity, as a sentence.
 *
 * A row with a book is clickable; one without (a streak, a join) is a plain div, because a control
 * that looks pressable and does nothing is worse than plain text.
 */
@Composable
internal fun ActivityRow(
    item: ActivityUiModel,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val parts = activityParts(item)
    val bookId = item.bookId

    val body: @Composable () -> Unit = {
        Span(attrs = { classes("disc-feed-line") }) {
            PersonLink(item.userDisplayName, item.userId, onOpenProfile, "disc-feed-who")
            Text(" ${parts.predicate}")
            parts.highlight?.let { highlight ->
                Text(" ")
                Span(attrs = { classes("disc-feed-hi") }) { Text(highlight) }
            }
            if (parts.suffix.isNotEmpty()) {
                Text(parts.suffix)
            }
        }
        Span(attrs = { classes("disc-when") }) { Text(relativeLastActive(item.occurredAt, nowMs)) }
    }

    if (bookId == null) {
        Div(attrs = { classes("disc-feed-row") }) { body() }
    } else {
        Button(attrs = {
            classes("disc-feed-row", "is-open")
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onOpenBook(bookId) }
        }) {
            body()
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    accessibleLabel: String? = null,
    onSelect: () -> Unit,
) {
    Button(attrs = {
        classes("disc-chip")
        accessibleLabel?.let { attr("aria-label", it) }
        if (selected) classes("is-on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        // Pressed, not selected: these are buttons in a group, not a listbox, and `aria-pressed`
        // is what a screen reader reads as "on" for a toggle-shaped control.
        attr("aria-pressed", selected.toString())
        onClick { onSelect() }
    }) {
        Text(label)
    }
}

/** A period the board offers: the short word its tab shows, and the full window a screen reader hears. */
private data class PeriodTab(
    val period: LeaderboardPeriod,
    val label: String,
    val accessibleLabel: String,
)

/**
 * The periods the board offers. They are trailing windows (today plus the days before it), so the
 * labels name the window, not a calendar unit.
 */
private val PERIODS: List<PeriodTab> =
    listOf(
        PeriodTab(LeaderboardPeriod.Week, "7 days", "Last 7 days"),
        PeriodTab(LeaderboardPeriod.Month, "30 days", "Last 30 days"),
        PeriodTab(LeaderboardPeriod.Year, "12 months", "Last 12 months"),
        PeriodTab(LeaderboardPeriod.AllTime, "All time", "All time"),
    )

private fun categoryLabel(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> "Time"
        LeaderboardCategory.Books -> "Books"
        LeaderboardCategory.Streak -> "Streak"
    }
