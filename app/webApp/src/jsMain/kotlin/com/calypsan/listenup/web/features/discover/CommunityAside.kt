package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
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
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.activityParts
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import com.calypsan.listenup.client.presentation.discover.leaderboardLabel
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Img
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
 * listened in is Empty, and the way out of it is another period. When you are alone the category
 * goes and the board closes with "Just you so far" (spec §3.8).
 */
@Composable
internal fun LeaderboardSection(
    state: LeaderboardUiState,
    selection: LeaderboardSelection,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRefresh: () -> Unit,
    community: CommunitySize,
    land: Boolean = false,
    onLanded: () -> Unit = {},
) {
    val currentOnLanded by rememberUpdatedState(onLanded)
    LaunchedEffect(land) {
        if (!land) return@LaunchedEffect
        val section = document.getElementById(LEADERBOARD_SECTION_ID) as? HTMLElement ?: return@LaunchedEffect
        section.scrollIntoView()
        (section.querySelector("h2") as? HTMLElement)?.let(::focusAsLanding)
        currentOnLanded()
    }
    Section(DiscoverWords.LEADERBOARD, id = LEADERBOARD_SECTION_ID, styleClass = "disc-lb-card") {
        ChipRadioGroup(DiscoverWords.PERIOD_GROUP, PERIOD_OPTIONS, selection.period, onSelectPeriod)
        // Only you: all three categories would rank you first, so the choice goes (spec §3.8).
        if (community != CommunitySize.JustYou) {
            ChipRadioGroup(
                DiscoverWords.CATEGORY_GROUP,
                CATEGORY_OPTIONS,
                selection.category,
                onSelectCategory,
                styleClass = "seg-radio",
            )
        }

        when (state) {
            is LeaderboardUiState.Loading -> {
                RowsSkeleton()
            }

            is LeaderboardUiState.Empty -> {
                when (community) {
                    // Never say "nobody" or "just you" before the roster has synced (plan C4).
                    CommunitySize.Unknown -> RowsSkeleton()

                    CommunitySize.JustYou -> QuietLine(DiscoverWords.LEADERBOARD_JUST_YOU)

                    CommunitySize.Others -> QuietLine(DiscoverWords.LEADERBOARD_EMPTY_PERIOD)
                }
            }

            is LeaderboardUiState.Error -> {
                // Every section's failure offers the same way back: Try again, which always works.
                SectionError(DiscoverWords.ERROR_LEADERBOARD, onRetry = onRefresh)
            }

            is LeaderboardUiState.Data -> {
                val entries = leaderboardEntries(state.snapshot, selection.category)
                if (entries.isEmpty()) {
                    EmptyState(title = "Nothing in this category yet", body = "Try another period.")
                } else {
                    LeaderboardRows(entries, selection.category, onOpenProfile)
                    if (community == CommunitySize.JustYou) QuietLine(DiscoverWords.LEADERBOARD_JUST_YOU)
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
                LeaderRow(entry, category, onOpenProfile, asideOnly = !expanded && index >= PHONE_LEADERS)
            }
        }
    }
    if (entries.size > PHONE_LEADERS) {
        // At exactly six the desktop aside already shows everyone; the toggle is for phones only.
        ShowMoreToggle(
            styleClass = "disc-show-all",
            expanded = expanded,
            phoneOnly = !expanded && entries.size <= ASIDE_LEADERS,
            collapsedLabel = DiscoverWords.showAll(entries.size),
            onToggle = { expanded = !expanded },
        )
    }
}

/** One ranked person: a link to them, read "Rank N" first, with a medal on the podium and You on your row. */
@Composable
private fun LeaderRow(
    entry: LeaderboardEntry,
    category: LeaderboardCategory,
    onOpenProfile: (String) -> Unit,
    asideOnly: Boolean,
) {
    Li(attrs = {
        classes("disc-lb-row")
        if (entry.isYou) classes("is-you")
        if (asideOnly) classes("disc-aside-extra")
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

/**
 * "Show all N" / "See all activity →", then "Show less": a section's in-place expansion. [phoneOnly]
 * hides it beside the main column, where the wider cap already shows every row.
 */
@Composable
private fun ShowMoreToggle(
    styleClass: String,
    expanded: Boolean,
    phoneOnly: Boolean,
    collapsedLabel: String,
    onToggle: () -> Unit,
) {
    Button(
        kind = ButtonKind.Ghost,
        onClick = onToggle,
        attrs = {
            classes(styleClass)
            if (phoneOnly) classes("disc-phone-only")
        },
    ) { Text(if (expanded) DiscoverWords.SHOW_LESS else collapsedLabel) }
}

/**
 * "Activity": who did what, most recent first, in the one shared sentence ([activityParts]).
 *
 * Six rows on a phone, eight beside the main column (rows past six wear `disc-aside-extra`), then
 * "See all activity →", a link to `/discover/activity` (X33). It never expands in place. With seven
 * or eight rows the aside already shows them all, so the link is for phones only.
 *
 * @param onOpenAllActivity Opens See all activity; null leaves the link out.
 * @param onShelveOne Shelves a row's one book from its ⋯ menu; null leaves that action out.
 */
@Composable
internal fun ActivityFeedSection(
    state: ActivityFeedUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenAllActivity: (() -> Unit)? = null,
    onShelveOne: ((String) -> Unit)? = null,
) {
    Section(DiscoverWords.ACTIVITY) {
        when (state) {
            is ActivityFeedUiState.Loading -> {
                RowsSkeleton()
            }

            is ActivityFeedUiState.Error -> {
                SectionError(DiscoverWords.ERROR_ACTIVITY, onRetry = onRefresh)
            }

            is ActivityFeedUiState.Ready -> {
                if (state.isEmpty) {
                    QuietLine(DiscoverWords.NO_ACTIVITY)
                } else {
                    val activities = state.activities
                    ActivityRows(
                        activities.take(ASIDE_ACTIVITY),
                        nowMs,
                        onOpenBook,
                        onOpenShelf,
                        onOpenProfile,
                        onShelveOne,
                        asideFrom = PHONE_ACTIVITY,
                    )
                    if (onOpenAllActivity != null && activities.size > PHONE_ACTIVITY) {
                        AppLink(Routes.discoverActivity.toUrl(), onOpen = onOpenAllActivity, attrs = {
                            classes("disc-see-all")
                            if (activities.size <= ASIDE_ACTIVITY) classes("disc-phone-only")
                        }) { Text(DiscoverWords.SEE_ALL_ACTIVITY) }
                    }
                }
            }
        }
    }
}

/**
 * Activity rows in a feed; rows from [asideFrom] on wear `disc-aside-extra` (shown beside the main
 * column only). Shared by the section and the See all activity page (plan C6).
 */
@Composable
internal fun ActivityRows(
    activities: List<ActivityUiModel>,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShelveOne: ((String) -> Unit)?,
    asideFrom: Int = Int.MAX_VALUE,
) {
    Div(attrs = { classes("disc-feed") }) {
        activities.forEachIndexed { index, item ->
            key(item.id) {
                ActivityRow(
                    item = item,
                    nowMs = nowMs,
                    onOpenBook = onOpenBook,
                    onOpenShelf = onOpenShelf,
                    onOpenProfile = onOpenProfile,
                    onShelveOne = onShelveOne,
                    asideOnly = index >= asideFrom,
                )
            }
        }
    }
}

/**
 * One activity, as two links and a ⋯: the person's avatar (to them), then the body — the sentence, when, and
 * a book row's cover — as ONE link to what the sentence is about: the book, else the shelf, else the
 * person (spec X27–X33). The body is named by the whole sentence and its time, so a screen reader
 * hears one stop that says what happened, not three fragments and a name it already passed. Then
 * the row's ⋯ menu ([ActivityRowMenu]), for any row about a book or a shelf.
 */
@Composable
internal fun ActivityRow(
    item: ActivityUiModel,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShelveOne: ((String) -> Unit)? = null,
    asideOnly: Boolean = false,
) {
    val parts = activityParts(item)
    val bookId = item.bookId
    val shelfId = item.shelfId
    val who = if (item.isYou) DiscoverWords.YOU else item.userDisplayName
    val profileHref = Routes.profile(item.userId).toUrl()
    val (bodyHref, openBody) =
        when {
            bookId != null -> Routes.Book.detail(bookId).toUrl() to { onOpenBook(bookId) }
            shelfId != null -> Routes.shelf(shelfId).toUrl() to { onOpenShelf(shelfId) }
            else -> profileHref to { onOpenProfile(item.userId) }
        }
    Div(attrs = {
        classes("disc-feed-row")
        if (asideOnly) classes("disc-aside-extra")
    }) {
        AppLink(profileHref, onOpen = { onOpenProfile(item.userId) }, attrs = {
            classes("disc-feed-avatar")
            attr("aria-label", DiscoverWords.profileLabel(item.userDisplayName, item.isYou))
        }) { UserAvatar(userId = item.userId, name = item.userDisplayName, size = FEED_AVATAR) }
        AppLink(bodyHref, onOpen = openBody, attrs = {
            classes("disc-feed-link")
            attr(
                "aria-label",
                DiscoverWords.activityLabel(
                    sentence = activitySentence(who, parts.predicate, parts.highlight, parts.suffix),
                    whenText = relativeLastActiveInSentence(item.occurredAt, nowMs),
                ),
            )
        }) {
            Span(attrs = { classes("disc-feed-body") }) {
                Span(attrs = { classes("disc-feed-line") }) {
                    B(attrs = { classes("disc-feed-who") }) { Text(who) }
                    Text(" ${parts.predicate}")
                    parts.highlight?.let { highlight ->
                        Text(" ")
                        B(attrs = { classes("disc-feed-hi") }) { Text(highlight) }
                    }
                    if (parts.suffix.isNotEmpty()) Text(parts.suffix)
                }
                Span(attrs = { classes("disc-when") }) { Text(relativeLastActive(item.occurredAt, nowMs)) }
            }
            bookId?.let {
                Img(src = coverUrl(it, item.bookCoverHash, width = FEED_COVER), alt = "", attrs = {
                    classes("disc-feed-cover")
                    attr("aria-hidden", "true")
                    attr("loading", "lazy")
                })
            }
        }
        // The third sibling, never inside either link: a button nested in a link is invalid.
        ActivityRowMenu(item, onOpenBook, onOpenShelf, onOpenProfile, onShelveOne)
    }
}

/** The row's sentence as it reads on screen: "Ada finished The Institute by Stephen King". */
private fun activitySentence(
    who: String,
    predicate: String,
    highlight: String?,
    suffix: String,
): String =
    buildString {
        append(who).append(' ').append(predicate)
        highlight?.let { append(' ').append(it) }
        append(suffix)
    }

/** Activity rows shown on a phone before "See all activity". */
private const val PHONE_ACTIVITY = 6

/** Activity rows shown beside the main column (≥ 1280px) before "See all activity". */
private const val ASIDE_ACTIVITY = 8

/** An activity row's avatar, in px. */
private const val FEED_AVATAR = 32

/** An activity row's cover, in px. */
private const val FEED_COVER = 36

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
