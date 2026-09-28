package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import listenup.composeapp.generated.resources.chapter_editor_edit_time_invalid
import listenup.composeapp.generated.resources.chapter_editor_edit_time_label
import listenup.composeapp.generated.resources.chapter_editor_edit_time_title
import com.calypsan.listenup.client.core.ChapterTimeFormat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import listenup.composeapp.generated.resources.chapter_editor_rename_label
import listenup.composeapp.generated.resources.chapter_editor_rename_title
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions

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

    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.chapter_editor_rename_title),
        confirmText = stringResource(Res.string.common_save),
        onConfirm = { onConfirm(trimmed) },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        confirmEnabled = trimmed.isNotEmpty(),
    ) {
        ListenUpTextField(
            value = title,
            onValueChange = { title = it },
            label = stringResource(Res.string.chapter_editor_rename_label),
            transform = { it.take(ChapterInput.MAX_TITLE) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
    }
}

/**
 * Typing a chapter's start exactly (spec §7.4, "tap to type to the ms").
 *
 * Opens on the start as the row shows it and takes back that shape and the shorter ones people
 * type; anything that is not a time is refused inline, with Save disabled, rather than guessed at.
 * The value is applied through the ViewModel's retime, which clamps it between the neighbours.
 */
@Composable
internal fun ChapterTimeDialog(
    initialMs: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(ChapterTimeFormat.precise(initialMs)) }
    val parsed = ChapterTimeFormat.parsePrecise(text)

    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.chapter_editor_edit_time_title),
        confirmText = stringResource(Res.string.common_save),
        onConfirm = { parsed?.let(onConfirm) },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        confirmEnabled = parsed != null,
    ) {
        Column {
            ListenUpTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(Res.string.chapter_editor_edit_time_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            if (parsed == null) {
                Text(
                    text = stringResource(Res.string.chapter_editor_edit_time_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
