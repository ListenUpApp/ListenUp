package com.calypsan.listenup.client.features.admin.categories.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.Genre
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_merge_into_named
import listenup.composeapp.generated.resources.admin_no_merge_target_available
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Merge picker — choose which live genre to merge the source into. Source is
 * filtered out of the candidate list. Confirms with the chosen target id.
 */
@Composable
internal fun MergeGenreDialog(
    sourceName: String,
    candidates: List<Genre>,
    onConfirm: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.admin_merge_into_named, sourceName)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(Res.string.admin_no_merge_target_available))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(candidates, key = { it.id }) { candidate ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onConfirm(candidate.id) }
                                    .padding(vertical = 12.dp),
                        ) {
                            Column {
                                Text(text = candidate.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = candidate.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
