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
 * Merge picker for genres — two-step, inside a single dialog window.
 *
 * Shares a contract with `SeriesMergeDialog`, not a shape: in both, tapping a candidate
 * only selects it — it never itself commits the merge. This dialog's second step carries
 * different content than the series one (book count, a permanence warning, no search
 * field), because merging a genre soft-deletes the source and re-links every one of its
 * books, and nothing in the product can reverse it.
 *
 * Both steps are rendered as slots of the same `AlertDialog` call, so the platform dialog
 * window is never torn down and remounted between them — no close/reopen animation, and
 * no spurious "new window" announcement for screen readers.
 *
 * The selection is held as the target's id, not a snapshot of the [Genre] itself, so the
 * confirm step always reflects the live candidate — if it's renamed or removed out from
 * under an open dialog, the step falls back to the candidate list rather than state a fact
 * that's no longer true.
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
    var selectedId by remember { mutableStateOf<String?>(null) }
    val target = candidates.firstOrNull { it.id == selectedId }
    if (selectedId != null && target == null) {
        // The selected candidate vanished from the live list (renamed/deleted mid-dialog) —
        // fall back to the candidate step rather than render a confirm screen for a genre
        // that no longer exists.
        selectedId = null
    }

    AlertDialog(
        onDismissRequest = {
            if (target != null) selectedId = null else onDismiss()
        },
        title = {
            if (target != null) {
                Text(stringResource(Res.string.admin_merge_genre_confirm_title, target.name))
            } else {
                Text(stringResource(Res.string.admin_merge_into_named, sourceName))
            }
        },
        text = {
            if (target != null) {
                ConfirmBody(
                    sourceName = sourceName,
                    sourceBookCount = sourceBookCount,
                    targetName = target.name,
                )
            } else {
                CandidateList(candidates = candidates, onSelect = { selectedId = it.id })
            }
        },
        confirmButton = {
            if (target != null) {
                TextButton(onClick = { onConfirm(target.id) }) {
                    Text(
                        text = stringResource(Res.string.admin_merge_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            if (target != null) {
                TextButton(onClick = { selectedId = null }) { Text(stringResource(Res.string.common_back)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun CandidateList(
    candidates: List<Genre>,
    onSelect: (Genre) -> Unit,
) {
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
}

@Composable
private fun ConfirmBody(
    sourceName: String,
    sourceBookCount: Int,
    targetName: String,
) {
    val body =
        if (sourceBookCount == 1) {
            stringResource(
                Res.string.admin_merge_genre_confirm_body,
                sourceName,
                targetName,
                sourceBookCount,
            )
        } else {
            stringResource(
                Res.string.admin_merge_genre_confirm_body_plural,
                sourceName,
                targetName,
                sourceBookCount,
            )
        }

    Column {
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.common_cannot_be_undone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
