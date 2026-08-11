package com.calypsan.listenup.client.features.admin.categories.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.Genre
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_merge_confirm
import listenup.composeapp.generated.resources.admin_merge_genre_confirm_body
import listenup.composeapp.generated.resources.admin_merge_genre_confirm_body_plural
import listenup.composeapp.generated.resources.admin_merge_genre_confirm_title
import listenup.composeapp.generated.resources.admin_merge_into_named
import listenup.composeapp.generated.resources.admin_no_merge_target_available
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_cannot_be_undone
import org.jetbrains.compose.resources.stringResource

/**
 * Merge picker for genres — two-step by design.
 *
 * Step one lists the live merge targets (the caller has already filtered out the source).
 * Tapping a candidate only *selects* it; step two then names both genres, states how many
 * books move, and warns that the merge cannot be undone. Only the explicit Merge button
 * commits, and Back returns to the list.
 *
 * The shape mirrors `SeriesMergeDialog`: merging soft-deletes the source and re-links every
 * one of its books, and nothing in the product can reverse it, so a single tap on a row in a
 * scrolling list must never be enough.
 *
 * @param sourceName Display name of the genre being merged away.
 * @param sourceBookCount Books linked to the source — the number that will move.
 * @param candidates Live merge targets, source already excluded.
 * @param onConfirm Called with the chosen target's id when the user taps Merge.
 * @param onDismiss Called on Cancel or dialog dismissal.
 */
@Composable
internal fun MergeGenreDialog(
    sourceName: String,
    sourceBookCount: Int,
    candidates: List<Genre>,
    onConfirm: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Genre?>(null) }

    val target = selected
    if (target == null) {
        CandidateStep(
            sourceName = sourceName,
            candidates = candidates,
            onSelect = { selected = it },
            onDismiss = onDismiss,
        )
    } else {
        ConfirmStep(
            sourceName = sourceName,
            sourceBookCount = sourceBookCount,
            target = target,
            onConfirm = { onConfirm(target.id) },
            onBack = { selected = null },
        )
    }
}

@Composable
private fun CandidateStep(
    sourceName: String,
    candidates: List<Genre>,
    onSelect: (Genre) -> Unit,
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
                                    .clickable { onSelect(candidate) }
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

@Composable
private fun ConfirmStep(
    sourceName: String,
    sourceBookCount: Int,
    target: Genre,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val body =
        if (sourceBookCount == 1) {
            stringResource(
                Res.string.admin_merge_genre_confirm_body,
                sourceName,
                target.name,
                sourceBookCount,
            )
        } else {
            stringResource(
                Res.string.admin_merge_genre_confirm_body_plural,
                sourceName,
                target.name,
                sourceBookCount,
            )
        }

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(Res.string.admin_merge_genre_confirm_title, target.name)) },
        text = {
            Column {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.common_cannot_be_undone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.admin_merge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.common_back)) }
        },
    )
}
