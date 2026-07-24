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
import androidx.compose.material3.OutlinedTextField
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
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.core.SeriesId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.series_merge_body
import listenup.composeapp.generated.resources.series_merge_confirm
import listenup.composeapp.generated.resources.series_merge_search_placeholder
import listenup.composeapp.generated.resources.series_merge_title
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
 * @param query Current search-query value; bound to the text field.
 * @param onQueryChange Called as the user types.
 * @param onConfirm Called with the highlighted candidate's id when the user taps Merge.
 * @param onDismiss Called when the user taps Cancel or dismisses the dialog.
 */
@Composable
fun SeriesMergeDialog(
    candidates: List<SeriesCandidate>,
    query: String,
    onQueryChange: (String) -> Unit,
    onConfirm: (SeriesId) -> Unit,
    onDismiss: () -> Unit,
) {
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
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(Res.string.common_search)) },
                    placeholder = {
                        Text(stringResource(Res.string.series_merge_search_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) {
                Text(stringResource(Res.string.series_merge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
                .clickable(onClick = onClick),
    )
}
