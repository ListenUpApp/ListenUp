package com.calypsan.listenup.web.features.chaptereditor

import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.P
import com.calypsan.listenup.client.core.ChapterTimeFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.ModalDialog

/**
 * Which of a row's dialogs is open, if any.
 *
 * One nullable value rather than two booleans, so "renaming and deleting at once" is not a state
 * that can be reached — the type says the dialogs are alternatives.
 */
internal sealed interface RowAction {
    val chapterId: String

    /** Editing the title. */
    data class Renaming(
        override val chapterId: String,
    ) : RowAction

    /** Confirming removal. */
    data class Deleting(
        override val chapterId: String,
    ) : RowAction

    /** Typing the start exactly. */
    data class EditingTime(
        override val chapterId: String,
    ) : RowAction
}

/** Rename a chapter. Opens on its current title, because renaming is usually a small correction. */
@Composable
internal fun RenameChapterDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }

    ModalDialog(open = true, title = "Rename chapter", onDismiss = onDismiss) {
        Field(label = "Title", value = title, onInput = { title = it }, id = "ced-chapter-title")
        DialogActions(
            confirmLabel = "Rename",
            onConfirm = { onConfirm(title) },
            onDismiss = onDismiss,
            // ⛔ A blank title is refused here rather than stored and caught at save. The ViewModel
            // refuses it too, but a Save that fails three edits later cannot say which one did it.
            confirmEnabled = title.isNotBlank(),
        )
    }
}

/** Confirm removing a boundary. Says where the span goes, because "delete" reads as losing audio. */
@Composable
internal fun DeleteChapterDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = true,
        title = "Delete this chapter?",
        body = "Its span joins the chapter before it. The audio is untouched.",
        confirmLabel = "Delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Confirm leaving with unsaved edits.
 *
 * ⛔ The editor holds the only copy of the user's work until they save, so every way out of the
 * page has to pass through here — the Back control and the browser's own Back alike.
 */
@Composable
internal fun DiscardChapterEditsDialog(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = true,
        title = "Discard your chapter edits?",
        body = "The changes you made here will be lost.",
        confirmLabel = "Discard",
        onConfirm = onDiscard,
        onDismiss = onDismiss,
    )
}

/**
 * Type a chapter's start exactly — spec §7.4's "tap to type to the ms".
 *
 * Opens on the start as the row shows it and accepts that shape and the shorter ones people type.
 * ⛔ Anything that is not a time is refused inline with Set disabled, never guessed at: a guessed
 * boundary lands somewhere nobody asked for. The ViewModel's retime clamps between neighbours.
 */
@Composable
internal fun ChapterTimeDialog(
    initialMs: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialMs) { mutableStateOf(ChapterTimeFormat.precise(initialMs)) }
    val parsed = ChapterTimeFormat.parsePrecise(text)

    ModalDialog(open = true, title = "Set start time", onDismiss = onDismiss) {
        Field(label = "Start time", value = text, onInput = { text = it }, id = "ced-chapter-time")
        if (parsed == null) {
            P(attrs = {
                classes("ched-field-err")
                attr("role", "alert")
            }) { Text("Type a time like 1:02:03.4.") }
        }
        DialogActions(
            confirmLabel = "Set",
            onConfirm = { parsed?.let(onConfirm) },
            onDismiss = onDismiss,
            confirmEnabled = parsed != null,
        )
    }
}
