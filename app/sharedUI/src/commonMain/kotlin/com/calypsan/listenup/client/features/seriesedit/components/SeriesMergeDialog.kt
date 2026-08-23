package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.core.SeriesId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_cannot_be_undone
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.series_merge_body
import listenup.composeapp.generated.resources.series_merge_book_count
import listenup.composeapp.generated.resources.series_merge_book_count_plural
import listenup.composeapp.generated.resources.series_merge_confirm
import listenup.composeapp.generated.resources.series_merge_no_matches
import listenup.composeapp.generated.resources.series_merge_search_placeholder
import listenup.composeapp.generated.resources.series_merge_title
import listenup.composeapp.generated.resources.series_merge_truncated
import org.jetbrains.compose.resources.stringResource

private const val LIST_MAX_HEIGHT_DP = 280
private const val SELECTED_BG_ALPHA = 0.4f

/**
 * Picker dialog for choosing a target series to merge the current series into.
 *
 * Two-step confirmation: the user must (1) tap a candidate, then (2) tap "Merge". The
 * merge action is destructive — the source series is soft-deleted and all of its books
 * are re-pointed at the target. Cancel is always reachable.
 *
 * Stateless besides local "currently highlighted candidate" selection — query + candidate
 * list are owned by the host ViewModel and passed in.
 *
 * @param candidates Live merge-target candidates (already filtered by [query] on the VM).
 * @param truncated Whether [candidates] is only the leading slice of a longer list. The list is
 *   capped and opens unfiltered, so on a real library the series the reader wants is usually
 *   absent — and a silently truncated list reads as a complete one.
 * @param query Current search-query value; bound to the text field.
 * @param bookCount Books in the source series — the number that will move.
 * @param onQueryChange Called as the user types.
 * @param onConfirm Called with the highlighted candidate's id when the user taps Merge.
 * @param onDismiss Called when the user taps Cancel or dismisses the dialog.
 */
@Composable
fun SeriesMergeDialog(
    candidates: List<SeriesCandidate>,
    truncated: Boolean,
    query: String,
    bookCount: Int,
    onQueryChange: (String) -> Unit,
    onConfirm: (SeriesId) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    var selected by remember { mutableStateOf<SeriesId?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.series_merge_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.series_merge_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        if (bookCount == 1) {
                            stringResource(Res.string.series_merge_book_count, bookCount)
                        } else {
                            stringResource(Res.string.series_merge_book_count_plural, bookCount)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.common_cannot_be_undone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ListenUpTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = stringResource(Res.string.common_search),
                    placeholder = stringResource(Res.string.series_merge_search_placeholder),
                    // Keep the pre-migration corner radius (the OutlinedTextField default).
                    shape = OutlinedTextFieldDefaults.shape,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Above the list, not below it: the list is capped at LIST_MAX_HEIGHT_DP, so a
                // notice underneath is pushed out of the dialog exactly when the list is long
                // enough to need one.
                if (candidates.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.series_merge_no_matches),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (truncated) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.series_merge_truncated, MAX_MERGE_CANDIDATES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(candidates, key = { it.id.value }) { candidate ->
                        CandidateRow(
                            name = candidate.displayName,
                            isSelected = selected == candidate.id,
                            onClick = { selected = candidate.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let {
                        haptics.commit()
                        onConfirm(it)
                    }
                },
                enabled = selected != null,
            ) {
                Text(
                    text = stringResource(Res.string.series_merge_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.press()
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

@Composable
private fun CandidateRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val highlight =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_BG_ALPHA)
        } else {
            Color.Transparent
        }
    ListItem(
        headlineContent = { Text(name) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(highlight)
                .clickable {
                    haptics.press()
                    onClick()
                },
    )
}
