package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.web.design.AppLink
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ChipOption
import com.calypsan.listenup.web.design.ChipRadioGroup
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.focusAsLanding
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.w3c.dom.HTMLElement
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
import org.jetbrains.compose.web.dom.Button as DomButton
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/*
 * Leaderboard and Activity: the community record. On the web desktop they sit in their own aside
 * beside the main column (spec §3.7).
 */

/**
 * The leaderboard: a period, a category, and a ranking, in one bordered card.
 *
 * Both controls are radio groups ([ChipRadioGroup]: one tab stop, arrows move the choice). All
 * three categories arrive in one snapshot, so switching a category is a list pick rather than a
 * query; the period genuinely re-queries, which is why it reads as the outer control.
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
    land: Boolean = false,
    onLanded: () -> Unit = {},
) {
    Section(
        DiscoverWords.LEADERBOARD,
        id = LEADERBOARD_SECTION_ID,
        styleClass = "disc-lb-card",
        onMounted = { section ->
            if (land) {
                section.scrollIntoView()
                (section.querySelector("h2") as? HTMLElement)?.let(::focusAsLanding)
                onLanded()
            }
        },
    ) {
        ChipRadioGroup(DiscoverWords.PERIOD_GROUP, PERIOD_OPTIONS, selection.period, onSelectPeriod)
        ChipRadioGroup(
            DiscoverWords.CATEGORY_GROUP,
            CATEGORY_OPTIONS,
            selection.category,
            onSelectCategory,
            styleClass = "seg-radio",
        )

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
                    LeaderboardRows(entries, selection.category, onOpenProfile)
                }
            }
        }
    }
}

/**
 * The ranking: five rows on a phone, six beside the main column (the sixth wears `disc-aside-extra`,
 * hidden below 1280px), then "Show all N". Each row is one link to the person, read rank first.
 */
@Composable
private fun LeaderboardRows(
    entries: List<LeaderboardEntry>,
    category: LeaderboardCategory,
    onOpenProfile: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) entries else entries.take(ASIDE_LEADERS)
    Ol(attrs = { classes("disc-lb") }) {
        shown.forEachIndexed { index, entry ->
            key(entry.userId) {
                Li(attrs = {
                    classes("disc-lb-row")
                    if (entry.isYou) classes("is-you")
                    if (!expanded && index >= PHONE_LEADERS) classes("disc-aside-extra")
                }) {
                    AppLink(Routes.profile(entry.userId).toUrl(), onOpen = { onOpenProfile(entry.userId) }) {
                        Span(attrs = {
                            classes("disc-rank")
                            if (entry.rank <= PODIUM) classes("is-medal", "medal-${entry.rank}")
                        }) {
                            Span(attrs = { classes("sr-only") }) { Text(DiscoverWords.RANK) }
                            Text("${entry.rank}")
                        }
                        UserAvatar(userId = entry.userId, name = entry.displayName, size = LEADER_AVATAR)
                        Span(attrs = { classes("disc-lb-name") }) {
                            Text(if (entry.isYou) DiscoverWords.YOU else entry.displayName)
                        }
                        Span(attrs = { classes("disc-lb-stat", "mono") }) { Text(leaderboardLabel(entry, category)) }
                    }
                }
            }
        }
    }
    if (entries.size > PHONE_LEADERS) {
        Button(
            kind = ButtonKind.Ghost,
            onClick = { expanded = !expanded },
            attrs = {
                classes("disc-show-all")
                // At exactly six the desktop aside already shows everyone; the toggle is for phones only.
                if (!expanded && entries.size <= ASIDE_LEADERS) classes("disc-phone-only")
            },
        ) { Text(if (expanded) DiscoverWords.SHOW_LESS else DiscoverWords.showAll(entries.size)) }
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
        DomButton(attrs = {
            classes("disc-feed-row", "is-open")
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onOpenBook(bookId) }
        }) {
            body()
        }
    }
}

/** Leaders shown on a phone before "Show all". */
private const val PHONE_LEADERS = 5

/** Leaders shown beside the main column (≥ 1280px) before "Show all". */
private const val ASIDE_LEADERS = 6

/** Ranks that wear a medal. */
private const val PODIUM = 3

/** A leader's avatar, in px. */
private const val LEADER_AVATAR = 28

/** The leaderboard section's element id: the landing's scroll target. */
internal const val LEADERBOARD_SECTION_ID = "leaderboard"

/** The periods, one word each, the same on every platform. */
private val PERIOD_OPTIONS: List<ChipOption<LeaderboardPeriod>> =
    listOf(
        ChipOption(LeaderboardPeriod.Week, "Week"),
        ChipOption(LeaderboardPeriod.Month, "Month"),
        ChipOption(LeaderboardPeriod.Year, "Year"),
        ChipOption(LeaderboardPeriod.AllTime, "All time"),
    )

private val CATEGORY_OPTIONS = LeaderboardCategory.entries.map { ChipOption(it, categoryLabel(it)) }

private fun categoryLabel(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> "Time"
        LeaderboardCategory.Books -> "Books"
        LeaderboardCategory.Streak -> "Streak"
    }
