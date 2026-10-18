package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RowsSkeleton
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity
import listenup.composeapp.generated.resources.discover_error_activity
import listenup.composeapp.generated.resources.discover_activity_show_less
import listenup.composeapp.generated.resources.discover_no_activity_yet_start_listening
import listenup.composeapp.generated.resources.discover_see_all_activity
import org.jetbrains.compose.resources.stringResource

/** Rows shown before "See all activity". */
private const val ACTIVITY_CAP = 6

/**
 * Discover's "Activity": what people on the server have been doing, newest first, in the one shared
 * sentence ([activityParts]). Your own rows read "You". Every row leads somewhere: a book row opens
 * the book, a shelf row the shelf, and a row with neither (a join, a milestone) the person. The
 * avatar is its own profile link beside the row's.
 *
 * Six rows show, then "See all activity". Until PR 6's See all screen exists the link expands the
 * section in place to the full feed and then reads "Show less"; PR 6 swaps this for navigation (plan Q1).
 *
 * Loading is a skeleton of rows and a failure a line with its own Try again (spec §3.8).
 */
@Composable
internal fun ActivityFeedSection(
    state: ActivityFeedUiState,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToShelf: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = stringResource(Res.string.discover_activity))

        when (val s = state) {
            is ActivityFeedUiState.Loading -> {
                RowsSkeleton(rows = ACTIVITY_CAP, modifier = Modifier.testTag("activity-skeleton"))
            }

            is ActivityFeedUiState.Error -> {
                InlineRetry(line = stringResource(Res.string.discover_error_activity), onRetry = onRetry)
            }

            is ActivityFeedUiState.Ready -> {
                if (s.isEmpty) {
                    Text(
                        text = stringResource(Res.string.discover_no_activity_yet_start_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ActivityRows(
                        activities = s.activities,
                        onBookClick = onBookClick,
                        onShelfClick = onShelfClick,
                        onUserClick = onUserClick,
                        onAddToShelf = onAddToShelf,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRows(
    activities: List<ActivityUiModel>,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onAddToShelf: ((String) -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) activities else activities.take(ACTIVITY_CAP)
    visible.forEachIndexed { index, activity ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActivityRow(
            activity = activity,
            onBookClick = onBookClick,
            onShelfClick = onShelfClick,
            onUserClick = onUserClick,
            onAddToShelf = onAddToShelf,
        )
    }
    if (activities.size > ACTIVITY_CAP) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    if (expanded) {
                        stringResource(Res.string.discover_activity_show_less)
                    } else {
                        stringResource(Res.string.discover_see_all_activity)
                    },
            )
        }
    }
}
