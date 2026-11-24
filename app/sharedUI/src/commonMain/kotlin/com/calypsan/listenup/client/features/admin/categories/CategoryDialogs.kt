
package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import listenup.composeapp.generated.resources.common_done
import listenup.composeapp.generated.resources.merge_history_genre_title
import com.calypsan.listenup.client.features.merge.MergeHistoryList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.features.admin.categories.components.MergeGenreDialog
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.genreMoveCandidates
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_subgenre
import listenup.composeapp.generated.resources.admin_confirm_delete_item
import listenup.composeapp.generated.resources.admin_genre_name
import listenup.composeapp.generated.resources.admin_move_to_named
import listenup.composeapp.generated.resources.admin_no_move_target_top_level_only
import listenup.composeapp.generated.resources.admin_rename_genre
import listenup.composeapp.generated.resources.admin_top_level
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_rename
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import listenup.composeapp.generated.resources.admin_add_root_genre

/**
 * One genre's "Merged into this" list with Undo (#1061), opened from the row menu. A dialog rather
 * than a page: it is a short list with one action, and the tree it came from stays behind it.
 */
@Composable
internal fun GenreMergeHistorySheet(viewModel: AdminCategoriesViewModel) {
    val open by viewModel.mergeHistory.collectAsStateWithLifecycle()
    val current = open ?: return
    ListenUpAlertDialog(
        onDismissRequest = viewModel::closeMergeHistory,
        title = stringResource(Res.string.merge_history_genre_title, current.genreName),
        confirmText = stringResource(Res.string.common_done),
        onConfirm = viewModel::closeMergeHistory,
        dismissText = null,
    ) {
        MergeHistoryList(
            state = current.history,
            onUndo = viewModel::undoGenreMerge,
            onRetry = viewModel::retryMergeHistory,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
internal fun CreateGenreDialog(
    viewModel: AdminCategoriesViewModel,
    showDialogState: MutableState<Boolean>,
    parentIdState: MutableState<String?>,
    parentNameState: MutableState<String?>,
) {
    var showCreateDialog by showDialogState
    val createParentId by parentIdState
    val createParentName by parentNameState

    if (showCreateDialog) {
        GenreNameDialog(
            title =
                if (createParentName != null) {
                    stringResource(Res.string.admin_add_subgenre)
                } else {
                    stringResource(Res.string.admin_add_root_genre)
                },
            subtitle = createParentName?.let { "Under: $it" },
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createGenre(name, createParentId)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
internal fun RenameGenreDialog(
    viewModel: AdminCategoriesViewModel,
    showDialogState: MutableState<Boolean>,
    genreIdState: MutableState<String>,
    genreNameState: MutableState<String>,
) {
    var showRenameDialog by showDialogState
    val renameGenreId by genreIdState
    val renameGenreName by genreNameState

    if (showRenameDialog) {
        GenreNameDialog(
            title = stringResource(Res.string.admin_rename_genre),
            initialName = renameGenreName,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = { name ->
                viewModel.renameGenre(renameGenreId, name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
}

@Composable
internal fun DeleteGenreDialog(
    viewModel: AdminCategoriesViewModel,
    showDialogState: MutableState<Boolean>,
    genreIdState: MutableState<String>,
    genreNameState: MutableState<String>,
) {
    var showDeleteDialog by showDialogState
    val deleteGenreId by genreIdState
    val deleteGenreName by genreNameState

    if (showDeleteDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(Res.string.common_delete_name, "Genre"),
            text = stringResource(Res.string.admin_confirm_delete_item, deleteGenreName),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                viewModel.deleteGenre(deleteGenreId)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
internal fun MergeGenreDialogHost(
    viewModel: AdminCategoriesViewModel,
    state: AdminCategoriesUiState,
    showDialogState: MutableState<Boolean>,
    sourceIdState: MutableState<String>,
    sourceNameState: MutableState<String>,
) {
    var showMergeDialog by showDialogState
    val mergeSourceId by sourceIdState
    val mergeSourceName by sourceNameState

    if (showMergeDialog) {
        val ready = state as? AdminCategoriesUiState.Ready
        val source = ready?.run { genres.firstOrNull { it.id == mergeSourceId } }
        MergeGenreDialog(
            sourceName = mergeSourceName,
            // source is only null if the genre vanished from the live list mid-dialog (this
            // dialog is opened from a row in that same list) — the merge would fail
            // server-side anyway, so 0 is a safe placeholder rather than a real count.
            sourceBookCount = source?.bookCount ?: 0,
            candidates = ready?.genres.orEmpty().filter { it.id != mergeSourceId },
            onConfirm = { targetId ->
                viewModel.mergeGenres(mergeSourceId, targetId)
                showMergeDialog = false
            },
            onDismiss = { showMergeDialog = false },
        )
    }
}

@Composable
internal fun MoveGenreDialogHost(
    viewModel: AdminCategoriesViewModel,
    state: AdminCategoriesUiState,
    showDialogState: MutableState<Boolean>,
    sourceIdState: MutableState<String>,
    sourceNameState: MutableState<String>,
) {
    var showMoveDialog by showDialogState
    val moveSourceId by sourceIdState
    val moveSourceName by sourceNameState

    if (showMoveDialog) {
        val ready = state as? AdminCategoriesUiState.Ready
        val source = ready?.run { genres.firstOrNull { it.id == moveSourceId } }
        MoveGenreDialog(
            sourceName = moveSourceName,
            candidates =
                if (ready != null && source != null) genreMoveCandidates(ready.genres, source) else emptyList(),
            onConfirmTarget = { targetId ->
                viewModel.moveGenre(moveSourceId, targetId)
                showMoveDialog = false
            },
            onConfirmTopLevel = {
                viewModel.moveGenre(moveSourceId, null)
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
}

/**
 * Reusable dialog for entering/editing a genre name.
 */
@Composable
private fun GenreNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val name = rememberTextFieldState(initialName)

    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = confirmLabel,
        onConfirm = { onConfirm(name.text.toString().trim()) },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        confirmEnabled = name.text.isNotBlank(),
    ) {
        Column {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            ListenUpTextField(
                state = name,
                label = stringResource(Res.string.admin_genre_name),
                // Keep the pre-migration corner radius (the OutlinedTextField default).
                shape = OutlinedTextFieldDefaults.shape,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        }
    }
}

/**
 * Move picker — choose a new parent for the source genre, or promote it to the
 * top level. The manual fallback for drag-based reparenting (Never Stranded).
 * Candidates already exclude the source and its descendants (cycle-safe).
 */
@Composable
private fun MoveGenreDialog(
    sourceName: String,
    candidates: List<Genre>,
    onConfirmTarget: (targetId: String) -> Unit,
    onConfirmTopLevel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.admin_move_to_named, sourceName),
        confirmText = null,
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "__top_level__", contentType = "top-level") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.selectionTick()
                                onConfirmTopLevel()
                            }.padding(vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.admin_top_level),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (candidates.isEmpty()) {
                item(key = "__no_candidates__", contentType = "empty") {
                    Text(
                        text = stringResource(Res.string.admin_no_move_target_top_level_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(
                items = candidates,
                key = { it.id },
                contentType = { "candidate" },
            ) { candidate ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.selectionTick()
                                onConfirmTarget(candidate.id)
                            }.padding(vertical = 12.dp),
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
