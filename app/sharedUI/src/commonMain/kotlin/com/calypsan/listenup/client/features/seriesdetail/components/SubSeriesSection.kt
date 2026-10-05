package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.seriesdetail.ChildSeriesUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_offline
import listenup.composeapp.generated.resources.series_add_subseries
import listenup.composeapp.generated.resources.series_book_finished
import listenup.composeapp.generated.resources.series_finished_count
import listenup.composeapp.generated.resources.series_not_started
import listenup.composeapp.generated.resources.series_subseries
import listenup.composeapp.generated.resources.series_subseries_count
import org.jetbrains.compose.resources.stringResource

/** Narrowest a sub-series card gets before the grid drops a column; grows with the font scale. */
private val SubSeriesCardMinWidth = 156.dp
private val CardGap = 12.dp

/**
 * The "Sub-series" section of a parent page: a card per direct sub-series in sibling order, and —
 * for an editor — an "Add sub-series" tile, disabled while offline because the change needs the
 * server. Columns flow with the width; at large text the cards drop to one column rather than
 * squeezing their names.
 */
@Composable
internal fun SubSeriesSection(
    childSeries: List<ChildSeriesUi>,
    canAddSubSeries: Boolean,
    isOnline: Boolean,
    onSeriesClick: (seriesId: String) -> Unit,
    onAddSubSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CardGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.series_subseries),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
            CountBadge(count = childSeries.size)
        }
        SubSeriesGrid(childSeries = childSeries, onSeriesClick = onSeriesClick)
        if (canAddSubSeries) {
            AddSubSeriesTile(enabled = isOnline, onClick = onAddSubSeries)
        }
    }
}

@Composable
private fun SubSeriesGrid(
    childSeries: List<ChildSeriesUi>,
    onSeriesClick: (String) -> Unit,
) {
    val minWidth = SubSeriesCardMinWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + CardGap) / (minWidth + CardGap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            childSeries.chunked(columns).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                    rowCards.forEach { child ->
                        SubSeriesCard(
                            child = child,
                            onClick = { onSeriesClick(child.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One sub-series: its lead cover (with a stacked edge when it holds sub-series of its own), name,
 * "8 books · 2 finished", and a progress bar. TalkBack reads the card as one node —
 * "Mistborn, 2 series, 8 books, 2 finished" — never as a percentage.
 */
@Composable
internal fun SubSeriesCard(
    child: ChildSeriesUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val books = bookCountLabel(child.bookCount)
    val progressLabel =
        when {
            child.isFinished -> stringResource(Res.string.series_book_finished)
            child.isNotStarted -> stringResource(Res.string.series_not_started)
            else -> stringResource(Res.string.series_finished_count, child.finishedCount)
        }
    val seriesCount =
        if (child.subSeriesCount > 0) stringResource(Res.string.series_subseries_count, child.subSeriesCount) else null
    val description = listOfNotNull(child.name, seriesCount, books, progressLabel).joinToString(", ")
    val open = {
        haptics.press()
        onClick()
    }

    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(onClick = open)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        open()
                        true
                    }
                }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SubSeriesCover(child = child)
            Spacer(Modifier.weight(1f))
            if (seriesCount != null) {
                Text(
                    text = seriesCount,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = child.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$books · ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (child.isFinished) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
            }
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (child.isFinished) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (child.bookCount == 0) 0f else child.finishedCount.toFloat() / child.bookCount },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
    }
}

/** The lead cover; a series with sub-series of its own wears a second, offset layer behind it. */
@Composable
private fun SubSeriesCover(child: ChildSeriesUi) {
    val stacked = child.subSeriesCount > 0
    Box(modifier = Modifier.padding(top = if (stacked) 6.dp else 0.dp, end = if (stacked) 6.dp else 0.dp)) {
        if (stacked) {
            Box(
                Modifier
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        SeriesCoverThumb(seriesId = child.id, name = child.name, coverPath = child.coverPath, size = 72.dp)
    }
}

/** The editor-only "Add sub-series" tile. Offline it stays visible but disabled, and says why. */
@Composable
private fun AddSubSeriesTile(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    OutlinedCard(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(
                text = stringResource(Res.string.series_add_subseries),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (!enabled) {
                Text(
                    text = stringResource(Res.string.common_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
