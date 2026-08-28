package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpTextField
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_delete_body
import listenup.composeapp.generated.resources.chapter_editor_delete_title
import listenup.composeapp.generated.resources.chapter_editor_discard_body
import listenup.composeapp.generated.resources.chapter_editor_discard_title
import listenup.composeapp.generated.resources.chapter_editor_more
import listenup.composeapp.generated.resources.chapter_editor_rename_label
import listenup.composeapp.generated.resources.chapter_editor_rename_title
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource

/**
 * Leaving with unsaved edits.
 *
 * Asked rather than assumed, because the draft is the only copy: the editor never writes as you
 * type, so a silent back gesture is the one action on this screen that can destroy work.
 */
@Composable
internal fun DiscardChapterEditsDialog(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.chapter_editor_discard_title),
        text = stringResource(Res.string.chapter_editor_discard_body),
        confirmText = stringResource(Res.string.common_discard),
        onConfirm = onDiscard,
        onDismiss = onDismiss,
    )
}

/**
 * Removing a boundary.
 *
 * Says what actually happens — the span merges into the chapter before it — rather than the vaguer
 * "cannot be undone", because deleting a chapter here never touches a byte of audio and implying
 * otherwise would make people hesitate over something safe.
 */
@Composable
internal fun DeleteChapterDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.chapter_editor_delete_title),
        text = stringResource(Res.string.chapter_editor_delete_body),
        confirmText = stringResource(Res.string.common_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = Icons.Default.Delete,
    )
}

/**
 * Retitling one chapter.
 *
 * The field caps at the contract's own limit and save is refused while the title is blank, so the
 * two rules `ChapterInput` would otherwise throw on are enforced where the user can still see them.
 */
@Composable
internal fun RenameChapterDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    val trimmed = title.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.chapter_editor_rename_title)) },
        text = {
            ListenUpTextField(
                value = title,
                onValueChange = { title = it },
                label = stringResource(Res.string.chapter_editor_rename_label),
                transform = { it.take(ChapterInput.MAX_TITLE) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * A row's overflow, as a dialog rather than an anchored menu.
 *
 * The row's actions have to be reachable identically on a phone, a desktop window and a browser,
 * and a dialog is the one shape that behaves the same in all three without each platform needing
 * its own anchoring rules.
 */
@Composable
internal fun ChapterActionsDialog(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.chapter_editor_more)) },
        text = {
            Column {
                TextButton(onClick = onRename) { Text(stringResource(Res.string.chapter_editor_rename_title)) }
                TextButton(onClick = onDelete) { Text(stringResource(Res.string.chapter_editor_delete_title)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
