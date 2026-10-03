package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.ScoreRow
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_checking
import listenup.composeapp.generated.resources.book_detail_rating_count
import listenup.composeapp.generated.resources.book_detail_rating_count_one
import listenup.composeapp.generated.resources.book_detail_rating_listeners_a11y
import listenup.composeapp.generated.resources.book_detail_rating_listeners_a11y_one
import listenup.composeapp.generated.resources.book_detail_rating_no_ratings
import listenup.composeapp.generated.resources.book_detail_rating_refresh
import listenup.composeapp.generated.resources.book_detail_rating_score
import listenup.composeapp.generated.resources.book_detail_rating_score_a11y
import listenup.composeapp.generated.resources.book_detail_rating_score_a11y_one
import listenup.composeapp.generated.resources.book_detail_rating_score_detail
import listenup.composeapp.generated.resources.book_detail_rating_show_sources
import listenup.composeapp.generated.resources.rating_source_listeners
import listenup.composeapp.generated.resources.rating_source_listeners_inline
import org.jetbrains.compose.resources.stringResource

/** The width of a row's leading figure, so the numbers line up and the dividers inset past them. */
private val FigureWidth = 64.dp

/** Where a divider starts: past the figure and the list item's own gap. */
private val DividerInset = 80.dp

/**
 * "Everyone", the second half of the rating block: the ListenUp score's row ([BookRatingsUiState.Ready.scoreRow])
 * and your listeners' row, as M3 list items with one notation — "★ 4.6", then "12k ratings".
 */
@Composable
internal fun RatingsEveryoneRows(
    ready: BookRatingsUiState.Ready,
    onOpenSources: () -> Unit,
    onRefreshExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scoreRow = ready.scoreRow
    Column(modifier = modifier.fillMaxWidth()) {
        when (scoreRow) {
            is ScoreRow.Shown -> ScoreItem(scoreRow.score, ready, onOpenSources)
            ScoreRow.Checking -> CheckingItem()
            ScoreRow.NoRatings -> NoRatingsRow(ready, onRefreshExternal)
            ScoreRow.Absent -> Unit
        }
        ready.listeners?.let { listeners ->
            if (scoreRow is ScoreRow.Shown || scoreRow == ScoreRow.Checking) {
                HorizontalDivider(modifier = Modifier.padding(start = DividerInset))
            }
            ListenersItem(listeners)
        }
        if (scoreRow == ScoreRow.Absent && ready.showsInlineRefresh) {
            QuietRefresh(isRefreshing = ready.isRefreshingExternal, onRefresh = onRefreshExternal)
        }
    }
}

@Composable
private fun ScoreItem(
    score: CombinedScore,
    ready: BookRatingsUiState.Ready,
    onOpenSources: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val average = averageLabel(score.average)
    val sources = scoreSourcesLabel(ready)
    val spoken =
        if (score.count == 1) {
            stringResource(Res.string.book_detail_rating_score_a11y_one, average, sources)
        } else {
            stringResource(Res.string.book_detail_rating_score_a11y, average, compactCount(score.count), sources)
        }
    val showSources = stringResource(Res.string.book_detail_rating_show_sources)
    ListItem(
        headlineContent = { Text(stringResource(Res.string.book_detail_rating_score)) },
        supportingContent = {
            Text(stringResource(Res.string.book_detail_rating_score_detail, ratingCountLabel(score.count), sources))
        },
        leadingContent = { Figure(average) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // ⛔ Order matters (a11y audit M4): `clickable` sits OUTSIDE `clearAndSetSemantics`, which clears
        // every semantics node to its right — the old rating line put it first, so TalkBack said
        // "button" and had no click to perform. The 48 dp floor is the audit's other half.
        modifier =
            Modifier
                .testTag("listenUpScoreRow")
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = showSources, role = Role.Button) {
                    haptics.press()
                    onOpenSources()
                }.clearAndSetSemantics { contentDescription = spoken },
    )
}

@Composable
private fun CheckingItem() {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.book_detail_rating_score)) },
        supportingContent = { Text(stringResource(Res.string.book_detail_rating_checking)) },
        leadingContent = {
            Box(modifier = Modifier.width(FigureWidth), contentAlignment = Alignment.Center) {
                ListenUpLoadingIndicatorSmall()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .testTag("checkingHardcoverRow")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun NoRatingsRow(
    ready: BookRatingsUiState.Ready,
    onRefreshExternal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.book_detail_rating_no_ratings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ready.showsInlineRefresh) {
            QuietRefresh(
                isRefreshing = ready.isRefreshingExternal,
                onRefresh = onRefreshExternal,
            )
        }
    }
}

@Composable
private fun ListenersItem(listeners: ListenerAverage) {
    val label = RatingLabels.listenerAverageLabel(listeners)
    val spoken =
        if (listeners.count == 1) {
            stringResource(Res.string.book_detail_rating_listeners_a11y_one, label)
        } else {
            stringResource(Res.string.book_detail_rating_listeners_a11y, label, listeners.count)
        }
    ListItem(
        headlineContent = { Text(stringResource(Res.string.rating_source_listeners)) },
        supportingContent = { Text(ratingCountLabel(listeners.count)) },
        leadingContent = { Figure(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/** The quiet "Refresh ratings" an admin sees where there is no score row to open the sources from. */
@Composable
internal fun QuietRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    TextButton(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier.testTag("refreshRatingsInlineButton"),
    ) { Text(stringResource(Res.string.book_detail_rating_refresh)) }
}

/** "★ 4.6": the star in primary, the average bold — the one notation every average uses. */
@Composable
private fun Figure(average: String) {
    Row(
        modifier = Modifier.width(FigureWidth),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(text = average, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** "12k ratings", or "1 rating". */
@Composable
internal fun ratingCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.book_detail_rating_count_one)
    } else {
        stringResource(Res.string.book_detail_rating_count, compactCount(count))
    }

/** "Audible, Hardcover, your listeners" — the sources the score draws on, in the breakdown's order. */
@Composable
private fun scoreSourcesLabel(ready: BookRatingsUiState.Ready): String {
    val outside = ready.outsideRatingsInScore.map { ratingSourceLabel(it.source) }
    val listeners =
        if (ready.listenersInScore) listOf(stringResource(Res.string.rating_source_listeners_inline)) else emptyList()
    return (outside + listeners).joinToString(", ")
}
