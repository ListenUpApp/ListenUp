package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.library.BookSelectionScaffold
import com.calypsan.listenup.web.features.library.FeedSelection
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * See all activity (X33): the whole community feed (up to the 100 rows the feed keeps), in the
 * section's own [ActivityRow] with its ⋯ menu (plan C6). One H1, "Activity", under a breadcrumb back
 * to Discover; Refresh asks the server for anything newer. Loading and failure match the section's.
 *
 * Pure in [state]; [ActivityFeedRoute] wires the session.
 */
@Composable
fun ActivityFeedPage(
    state: ActivityFeedUiState,
    nowMs: Long,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShelveOne: ((String) -> Unit)?,
) {
    Div(attrs = { classes("disc", "disc-feed-page") }) {
        Breadcrumb(trail = listOf("Discover", DiscoverWords.ACTIVITY), onNavigate = { onBack() })
        PageHeader(
            title = DiscoverWords.ACTIVITY,
            actions = {
                Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = onRefresh) {
                    Text(DiscoverWords.REFRESH)
                }
            },
        )
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
                    ActivityRows(state.activities, nowMs, onOpenBook, onOpenShelf, onOpenProfile, onShelveOne)
                }
            }
        }
    }
}

/** The `/discover/activity` branch: its own feed session, and one-book shelving through the shared scaffold. */
@Composable
internal fun ActivityFeedRoute(
    router: Router,
    openActivityFeed: OpenActivityFeed,
    feeds: FeedSelection,
) {
    val session = rememberSession { openActivityFeed() }
    val nowMs = remember { currentEpochMilliseconds() }
    BookSelectionScaffold(feeds.openMultiSelect, router, feeds.onToast) { selection ->
        ActivityFeedPage(
            state = session.state.collectAsState().value,
            nowMs = nowMs,
            onBack = { router.navigate(Routes.discover) },
            onRefresh = session.onRefresh,
            onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
            onOpenShelf = { id -> router.navigate(Routes.shelf(id)) },
            onOpenProfile = { id -> router.navigate(Routes.profile(id)) },
            onShelveOne = selection.onShelveOne,
        )
    }
}
