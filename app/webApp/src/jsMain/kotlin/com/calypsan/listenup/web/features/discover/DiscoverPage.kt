package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.match.TwoPaneWatch
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

/**
 * Discover — the social half of the app, and the only screen that shows the reader other people.
 *
 * Five sections, each rendering its own sealed state. That independence is the point: the
 * leaderboard failing must not blank the listeners row, and an activity feed still loading must not
 * hold back the books. One section's bad day costs exactly that section.
 *
 * Every sentence, stat and ranking on this page comes from a shared projection
 * (`activityParts`, `leaderboardLabel`, `leaderboardEntries`, `relativeLastActive`) rather than
 * being restated here. This screen is almost entirely copy, and copy that exists twice is copy that
 * disagrees with itself the first time either side is reworded.
 *
 * The shelves section groups by owner rather than listing shelves flat: on a shared server the
 * interesting unit is a person's taste, and a flat list of forty shelves says nothing about whose
 * they are.
 *
 * [nowMs] is passed in rather than read here so a row cannot flicker "3 days ago" to "4 days ago"
 * mid-recomposition, and so a spec can pin the clock. Same reasoning the Compose screen gives.
 */
@Composable
fun DiscoverPage(
    books: DiscoverBooksUiState,
    recentlyAdded: RecentlyAddedUiState,
    currentlyListening: CurrentlyListeningUiState,
    leaderboard: LeaderboardUiState,
    leaderboardSelection: LeaderboardSelection,
    activity: ActivityFeedUiState,
    shelves: DiscoverShelvesUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    selection: BookSelection? = null,
    onOpenShelf: (String) -> Unit,
    /**
     * Opens the person behind a name.
     *
     * ⛔ Discover is the one screen built to show you other people, and every name on it was inert
     * text — the profile page existed and route and all, with nothing on this page pointing at it.
     * Both natives link all four surfaces below.
     */
    onOpenProfile: (String) -> Unit,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
    /** Try again on a failed Listening now: that section's own restart (spec §3.8). */
    onRetryListening: () -> Unit,
    /** Try again on a failed Recently added: that section's own restart. */
    onRetryRecent: () -> Unit,
    /** Try again on a failed Shelves by others: refetches the shelves, nothing else. */
    onRetryShelves: () -> Unit,
    /** Re-runs a failed leaderboard — its own ViewModel's refresh, not Discover's. */
    onRefreshLeaderboard: () -> Unit,
    /** Re-runs a failed activity feed — its own ViewModel's refresh, not Discover's. */
    onRefreshActivity: () -> Unit,
    /** Draws ten different picks for Something new. */
    onShuffle: () -> Unit,
    /** Opens the Library's Books sorted newest first, for this visit (Recently added's link). */
    onOpenLibraryNewest: () -> Unit,
    /** Who else is on the server: chooses the only-you and nobody-listening treatments (spec §3.8). */
    communitySize: CommunitySize,
    /** Who is looking: the invitation's avatar, and whether Invite someone is theirs to offer. */
    viewer: DiscoverViewer?,
    /** Opens the invite page; shown to an admin only. */
    onInvite: (() -> Unit)?,
    /** Opens the Library from "You've started every book here." */
    onBrowseLibrary: (() -> Unit)?,
    /**
     * Arrived at `/discover?section=leaderboard` (the stats card's footer link): select Week, then
     * bring the leaderboard into view and focus its heading (spec §6.4).
     */
    landOnLeaderboard: Boolean = false,
    /** Opens See all activity (`/discover/activity`, X33); null leaves the link out. */
    onOpenAllActivity: (() -> Unit)? = null,
) {
    // Spent on the first landing, as Admin's pending landing is: a later period pick is the reader's own.
    var landing by remember(landOnLeaderboard) { mutableStateOf(landOnLeaderboard) }
    LaunchedEffect(landOnLeaderboard) { if (landOnLeaderboard) onSelectPeriod(LeaderboardPeriod.Week) }

    Div(attrs = { classes("disc") }) {
        PageHeader(title = "Discover", subtitle = DiscoverWords.PAGE_SUBTITLE)

        // The DOM is the reading order, and so the Tab order: spec order in one column, and the main
        // column before the Community aside once the grid sets the aside beside it (plan D3).
        var body by remember { mutableStateOf<HTMLElement?>(null) }
        var isWide by remember { mutableStateOf(false) }
        TwoPaneWatch(body, useTwoPane = { isWide = it }, twoPaneQuery = DISCOVER_WIDE_QUERY)
        Div(attrs = {
            classes("disc-body")
            ref { element ->
                body = element
                onDispose { body = null }
            }
        }) {
            discoverReadingOrder(isWide).forEach { block ->
                key(block) {
                    when (block) {
                        DiscoverBlock.ListeningNow -> {
                            ListeningNowSection(
                                currentlyListening,
                                nowMs,
                                onOpenBook,
                                onOpenProfile,
                                selection,
                                onRetryListening,
                                communitySize,
                                viewer,
                                onInvite,
                            )
                        }

                        DiscoverBlock.SomethingNew -> {
                            // A fresh draw is the picks' restart.
                            SomethingNewSection(
                                books,
                                onOpenBook,
                                onShuffle,
                                selection,
                                onRetry = onShuffle,
                                onBrowseLibrary = onBrowseLibrary,
                            )
                        }

                        DiscoverBlock.Community -> {
                            Aside(attrs = {
                                classes("disc-aside")
                                attr("aria-label", COMMUNITY_LABEL)
                            }) {
                                LeaderboardSection(
                                    leaderboard,
                                    leaderboardSelection,
                                    onSelectPeriod,
                                    onSelectCategory,
                                    onOpenProfile,
                                    onRefreshLeaderboard,
                                    community = communitySize,
                                    land = landing,
                                    onLanded = { landing = false },
                                )
                                ActivityFeedSection(
                                    activity,
                                    nowMs,
                                    onOpenBook,
                                    onOpenShelf,
                                    onOpenProfile,
                                    onRefreshActivity,
                                    onOpenAllActivity = onOpenAllActivity,
                                    onShelveOne = selection?.shelveOne,
                                )
                            }
                        }

                        DiscoverBlock.ShelvesByOthers -> {
                            ShelvesByOthersSection(shelves, onOpenShelf, onOpenProfile, onRetryShelves)
                        }

                        DiscoverBlock.RecentlyAdded -> {
                            RecentlyAddedSection(
                                recentlyAdded,
                                nowMs,
                                onOpenBook,
                                onOpenLibraryNewest,
                                selection,
                                onRetryRecent,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Discover's page-body blocks; the Community aside is the leaderboard and the activity feed together. */
internal enum class DiscoverBlock { ListeningNow, SomethingNew, Community, ShelvesByOthers, RecentlyAdded }

/**
 * The blocks in DOM order, which is the focus order. One column follows the spec (§2.8, D3): the
 * aside straight after Something new. Wide, the grid sets the aside in its own column, so the DOM
 * runs down the main column first and Tab never jumps across and back.
 */
internal fun discoverReadingOrder(isWide: Boolean): List<DiscoverBlock> =
    if (isWide) {
        listOf(
            DiscoverBlock.ListeningNow,
            DiscoverBlock.SomethingNew,
            DiscoverBlock.ShelvesByOthers,
            DiscoverBlock.RecentlyAdded,
            DiscoverBlock.Community,
        )
    } else {
        listOf(
            DiscoverBlock.ListeningNow,
            DiscoverBlock.SomethingNew,
            DiscoverBlock.Community,
            DiscoverBlock.ShelvesByOthers,
            DiscoverBlock.RecentlyAdded,
        )
    }

/** Where the aside moves beside the main column. Must match the `.disc-body` media query in 34-discover.css. */
internal const val DISCOVER_WIDE_QUERY = "(min-width: 1280px)"

/** The aside's landmark name: the leaderboard and the activity feed are the community record. */
private const val COMMUNITY_LABEL = "Community"
