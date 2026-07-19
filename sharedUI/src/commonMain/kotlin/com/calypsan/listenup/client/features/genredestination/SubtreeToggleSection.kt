package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.genredestination.FacetIdentity
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.SubGenre
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.genre_destination_include_sub_genres
import listenup.composeapp.generated.resources.genre_destination_scope_off
import listenup.composeapp.generated.resources.genre_destination_scope_off_excluded_suffix
import listenup.composeapp.generated.resources.genre_destination_scope_on
import listenup.composeapp.generated.resources.genre_destination_sub_genres_label
import listenup.composeapp.generated.resources.genre_destination_tap_to_narrow
import org.jetbrains.compose.resources.stringResource

/** Max sub-genre names spelled out in the toggle's "off" subtitle before collapsing to "+N excluded". */
private const val NAMED_SUB_GENRES_IN_SUBTITLE = 2

/**
 * The subtree-scope toggle row plus, when it widens the book set, the wrap of sub-genre chips
 * beneath it — the "Body" section of a genre destination page between the hero and the book grid.
 * Renders nothing when [GenreDestinationUiState.Ready.hasSubs] is false (a leaf genre has no
 * scope to toggle and no sub-genres to list).
 */
@Composable
internal fun SubtreeToggleSection(
    state: GenreDestinationUiState.Ready,
    onToggle: () -> Unit,
    onSubGenreClick: (String) -> Unit,
) {
    if (!state.hasSubs) return

    Column(modifier = Modifier.fillMaxWidth()) {
        SubtreeToggleRow(state = state, onToggle = onToggle)
        Spacer(modifier = Modifier.height(20.dp))
        SubGenresLabelRow()
        Spacer(modifier = Modifier.height(12.dp))
        SubGenreChipRow(subGenres = state.subGenres, onSubGenreClick = onSubGenreClick)
    }
}

@Composable
private fun SubtreeToggleRow(
    state: GenreDestinationUiState.Ready,
    onToggle: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val containerColor =
        if (state.includeSubGenres) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (state.includeSubGenres) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(containerColor)
                .clickable {
                    haptics.selectionTick()
                    onToggle()
                }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountTree,
            contentDescription = null,
            tint = contentColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.genre_destination_include_sub_genres),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = contentColor,
            )
            Text(
                text = scopeSubtitle(state),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
        Switch(checked = state.includeSubGenres, onCheckedChange = { onToggle() })
    }
}

/** The toggle's contextual subtitle — what "on"/"off" actually means for this genre right now. */
@Composable
private fun scopeSubtitle(state: GenreDestinationUiState.Ready): String =
    if (state.includeSubGenres) {
        stringResource(
            Res.string.genre_destination_scope_on,
            state.stats.bookCount,
            state.identity.name,
            state.subGenres.size,
        )
    } else {
        val named = state.subGenres.take(NAMED_SUB_GENRES_IN_SUBTITLE).joinToString(", ") { it.name }
        val excludedCount = state.subGenres.size - NAMED_SUB_GENRES_IN_SUBTITLE
        val summary =
            if (excludedCount > 0) {
                named + stringResource(Res.string.genre_destination_scope_off_excluded_suffix, excludedCount)
            } else {
                named
            }
        stringResource(Res.string.genre_destination_scope_off, state.stats.bookCount, state.identity.name, summary)
    }

@Composable
private fun SubGenresLabelRow() {
    Column {
        Text(
            text = stringResource(Res.string.genre_destination_sub_genres_label).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.genre_destination_tap_to_narrow),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubGenreChipRow(
    subGenres: List<SubGenre>,
    onSubGenreClick: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        subGenres.forEach { subGenre ->
            SubGenreChip(
                subGenre = subGenre,
                onClick = {
                    haptics.selectionTick()
                    onSubGenreClick(subGenre.genreId.value)
                },
            )
        }
    }
}

@Composable
private fun SubGenreChip(
    subGenre: SubGenre,
    onClick: () -> Unit,
) {
    val hueColor = genreHueColor(FacetIdentity.hue(subGenre.name))
    val shape = RoundedCornerShape(50)

    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(hueColor))
        Text(
            text = subGenre.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subGenre.bookCount > 0) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = subGenre.bookCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
