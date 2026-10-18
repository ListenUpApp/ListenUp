package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.RowsSkeleton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.features.discover.components.ACTIVITY_CAP
import com.calypsan.listenup.client.features.discover.components.ActivityRow
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.library.components.rememberBookSelectionScaffoldState
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity
import listenup.composeapp.generated.resources.discover_error_activity
import listenup.composeapp.generated.resources.discover_no_activity_yet_start_listening
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The narrowest an activity column gets before the grid drops a column: a sentence and a cover fit. */
private val ActivityColumnMinWidth = 360.dp

/**
 * See all activity (X33): the community feed in full (up to the 100 rows the feed keeps), in the
 * section's own [ActivityRow] with the same actions (plan C6). Pull to refresh asks the server for
 * anything newer. Backed by its own [ActivityFeedViewModel]; "Add {book} to a shelf" shelves one
 * book through this screen's own [BookSelectionScaffold].
 */
@Composable
fun ActivityFeedScreen(
    onBack: () -> Unit,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityFeedViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val selectionState = rememberBookSelectionScaffoldState()

    PlatformBackHandler(enabled = selectionMode is SelectionMode.Active) { multiSelect.exitSelectionMode() }

    Box(modifier = modifier.fillMaxSize()) {
        ActivityFeedContent(
            state = state,
            onBack = onBack,
            onRetry = viewModel::refresh,
            onRefresh = viewModel::refresh,
            onBookClick = onBookClick,
            onShelfClick = onShelfClick,
            onUserClick = onUserClick,
            onAddToShelf = { id -> selectionState.shelveOne(id, multiSelect) },
        )
        BookSelectionScaffold(multiSelect = multiSelect, state = selectionState)
    }
}

/**
 * The stateless body of [ActivityFeedScreen]: a bar titled "Activity" with Back, then the rows.
 * Loading and failure match the section's: a skeleton of rows, and one line with Try again.
 *
 * Rows flow into as many [ActivityColumnMinWidth] columns as the window holds (sharedUI rule 11):
 * one on a phone, two or more on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityFeedContent(
    state: ActivityFeedUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onAddToShelf: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    ListenUpScaffold(
        modifier = modifier,
        topBar = { ListenUpTopAppBar(title = stringResource(Res.string.discover_activity), onBack = onBack) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = {
                haptics.thresholdActivate()
                onRefresh()
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (state) {
                is ActivityFeedUiState.Loading -> {
                    Column(Modifier.padding(horizontal = Spacing.screenMargin)) {
                        RowsSkeleton(rows = ACTIVITY_CAP, modifier = Modifier.testTag("activity-skeleton"))
                    }
                }

                is ActivityFeedUiState.Error -> {
                    Column(Modifier.padding(horizontal = Spacing.screenMargin)) {
                        InlineRetry(line = stringResource(Res.string.discover_error_activity), onRetry = onRetry)
                    }
                }

                is ActivityFeedUiState.Ready -> {
                    if (state.isEmpty) {
                        Text(
                            text = stringResource(Res.string.discover_no_activity_yet_start_listening),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = ActivityColumnMinWidth),
                            contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                            modifier = Modifier.fillMaxSize().testTag("activity-feed"),
                        ) {
                            items(state.activities, key = { it.id }) { activity ->
                                Column(Modifier.fillMaxWidth()) {
                                    ActivityRow(
                                        activity = activity,
                                        onBookClick = onBookClick,
                                        onShelfClick = onShelfClick,
                                        onUserClick = onUserClick,
                                        onAddToShelf = onAddToShelf,
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
