package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.books.BookSelection
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Div

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
    /**
     * Asks Discover's ViewModel for a fresh load — offered as "Try again" on the four sections it
     * owns when one has failed. Not a page-level refresh: web deliberately has none.
     */
    onRefresh: () -> Unit,
    /** Re-runs a failed leaderboard — its own ViewModel's refresh, not Discover's. */
    onRefreshLeaderboard: () -> Unit,
    /** Re-runs a failed activity feed — its own ViewModel's refresh, not Discover's. */
    onRefreshActivity: () -> Unit,
    /** Draws ten different picks for Something new. */
    onShuffle: () -> Unit,
    /** Opens the Library's Books sorted newest first, for this visit (Recently added's link). */
    onOpenLibraryNewest: () -> Unit,
    /**
     * Arrived at `/discover?section=leaderboard` (the stats card's footer link): select Week, then
     * bring the leaderboard into view and focus its heading (spec §6.4).
     */
    landOnLeaderboard: Boolean = false,
) {
    // Spent on the first landing, as Admin's pending landing is: a later period pick is the reader's own.
    var landing by remember(landOnLeaderboard) { mutableStateOf(landOnLeaderboard) }
    LaunchedEffect(landOnLeaderboard) { if (landOnLeaderboard) onSelectPeriod(LeaderboardPeriod.Week) }

    Div(attrs = { classes("disc") }) {
        PageHeader(title = "Discover", subtitle = DiscoverWords.PAGE_SUBTITLE)

        // One DOM in spec order (§2.8): the CSS grid places the Community aside beside the main
        // column where there is room, and leaves it in reading order where there is not.
        Div(attrs = { classes("disc-body") }) {
            ListeningNowSection(currentlyListening, nowMs, onOpenBook, onOpenProfile, selection, onRefresh)
            SomethingNewSection(books, onOpenBook, onShuffle, selection, onRefresh)
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
                    land = landing,
                    onLanded = { landing = false },
                )
                ActivityFeedSection(activity, nowMs, onOpenBook, onOpenShelf, onOpenProfile, onRefreshActivity)
            }
            ShelvesByOthersSection(shelves, onOpenShelf, onOpenProfile, onRefresh)
            RecentlyAddedSection(recentlyAdded, nowMs, onOpenBook, onOpenLibraryNewest, selection, onRefresh)
        }
    }
}

/** The aside's landmark name: the leaderboard and the activity feed are the community record. */
private const val COMMUNITY_LABEL = "Community"
