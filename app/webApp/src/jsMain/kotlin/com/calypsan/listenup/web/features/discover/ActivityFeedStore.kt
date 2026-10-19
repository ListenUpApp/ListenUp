package com.calypsan.listenup.web.features.discover

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.web.nav.RouteSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open See all activity session (X33): the feed and its refresh, backed by a fresh
 * [ActivityFeedViewModel] of its own (plan C6), so leaving the page ends its scope.
 */
class ActivityFeedSession(
    val state: StateFlow<ActivityFeedUiState>,
    /** Asks the server for anything newer, and restarts a failed feed. */
    val onRefresh: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session: the graph in production ([graphActivityFeed]), fixed states in specs. */
typealias OpenActivityFeed = () -> ActivityFeedSession

/** The production source: one [ActivityFeedViewModel] in its own [ViewModelStore], cleared on [ActivityFeedSession.close]. */
fun graphActivityFeed(koin: Koin): OpenActivityFeed =
    {
        val activity = koin.get<ActivityFeedViewModel>()
        val store = ViewModelStore().apply { put(ACTIVITY_FEED_STORE_KEY, activity) }
        ActivityFeedSession(
            state = activity.state,
            onRefresh = activity::refresh,
            close = store::clear,
        )
    }

/** A session over a state that never changes, for specs. */
fun fixedActivityFeed(
    state: ActivityFeedUiState = ActivityFeedUiState.Loading,
    onRefresh: () -> Unit = {},
): OpenActivityFeed = { ActivityFeedSession(MutableStateFlow(state), onRefresh, close = {}) }

private const val ACTIVITY_FEED_STORE_KEY = "discover-activity-feed"
