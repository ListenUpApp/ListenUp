package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_add_subseries
import listenup.composeapp.generated.resources.series_hierarchy_caption
import listenup.composeapp.generated.resources.series_move_into
import listenup.composeapp.generated.resources.series_offline_body
import listenup.composeapp.generated.resources.series_offline_title
import listenup.composeapp.generated.resources.series_part_of
import listenup.composeapp.generated.resources.series_subseries
import listenup.composeapp.generated.resources.series_top_level
import org.jetbrains.compose.resources.stringResource

/**
 * The editor's "Place in library" body: what the series is part of (with "Move into…"), its
 * sub-series (reorderable, plus "Add sub-series"), and the caption saying these apply at once and
 * need the server. Offline, a banner says so and every control here is disabled; the rest of the
 * editor still saves.
 */
@Composable
internal fun PlaceInLibrary(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val canChange = state.isOnline && !state.hierarchyBusy
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!state.isOnline) OfflineBanner()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    text = stringResource(Res.string.series_part_of),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.parentName ?: stringResource(Res.string.series_top_level),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.hierarchyBusy) ListenUpLoadingIndicatorSmall()
            FilledTonalButton(
                onClick = {
                    haptics.press()
                    onEvent(SeriesEditUiEvent.ParentPickerOpened)
                },
                enabled = canChange,
            ) {
                Text(stringResource(Res.string.series_move_into))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.series_subseries),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            CountBadge(count = state.childSeries.size)
        }
        if (state.childSeries.isNotEmpty()) {
            SubSeriesReorderList(
                children = state.childSeries,
                enabled = canChange,
                refusal = state.error,
                onReorder = { onEvent(SeriesEditUiEvent.ChildSeriesReordered(it)) },
            )
        }
        OutlinedButton(
            onClick = {
                haptics.press()
                onAddSubSeries()
            },
            enabled = canChange,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(Res.string.series_add_subseries), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            text = stringResource(Res.string.series_hierarchy_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.series_offline_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(Res.string.series_offline_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
