package com.calypsan.listenup.web.features.chaptereditor

import com.calypsan.listenup.client.core.ChapterTimeFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.client.presentation.chaptereditor.moveTargets
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.Button as DesignButton
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

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

    /** Naming a new marker, which is written only on confirm, or renaming one. */
    data class NamingMarker(
        override val chapterId: String,
        val tier: ChapterMarkerTier,
        val isNew: Boolean,
    ) : RowAction

    /** Choosing where a marker starts. */
    data class MovingMarker(
        override val chapterId: String,
        val tier: ChapterMarkerTier,
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

/**
 * Name a new marker, or rename one.
 *
 * ⛔ Blank and over-long are refused here, with the button disabled: `ChapterInput` throws on both,
 * and the ViewModel declining silently would look like a dialog that did nothing.
 */
@Composable
internal fun MarkerNameDialog(
    tier: ChapterMarkerTier,
    isNew: Boolean,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val tierName = tier.word.lowercase()
    val trimmed = name.trim()

    ModalDialog(
        open = true,
        title = if (isNew) "New $tierName" else "Rename $tierName",
        onDismiss = onDismiss,
    ) {
        Field(label = "Name", value = name, onInput = { name = it }, id = "ced-marker-name")
        DialogActions(
            confirmLabel = if (isNew) "Start $tierName" else "Rename",
            onConfirm = { onConfirm(trimmed) },
            onDismiss = onDismiss,
            confirmEnabled = trimmed.isNotEmpty() && trimmed.length <= ChapterInput.MAX_SECTION_TITLE,
        )
    }
}

/** Choose where a marker starts. A chapter already opening that tier is not on offer. */
@Composable
internal fun MarkerMoveDialog(
    tier: ChapterMarkerTier,
    chapters: List<Chapter>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = chapters.moveTargets(tier).map { it.id }.toSet()

    ModalDialog(open = true, title = "Choose a chapter", onDismiss = onDismiss) {
        Div(attrs = { classes("ched-pick") }) {
            chapters.numbered().filter { it.chapter.id in targets }.forEach { numbered ->
                key(numbered.chapter.id) {
                    Button(attrs = {
                        classes("ched-pick-i")
                        attr("type", "button")
                        onClick { onPick(numbered.chapter.id) }
                    }) {
                        Span(attrs = { classes("chr-n") }) { Text(numbered.number.toString()) }
                        Span(attrs = { classes("ched-pick-t") }) { Text(numbered.chapter.title) }
                        Span(attrs = { classes("ched-pick-at") }) {
                            Text(ChapterTimeFormat.clock(numbered.chapter.startTime))
                        }
                    }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            DesignButton(kind = ButtonKind.Secondary, onClick = { onDismiss() }) { Text("Cancel") }
        }
    }
}

/** The tier's name, as a person says it. */
internal val ChapterMarkerTier.word: String
    get() =
        when (this) {
            ChapterMarkerTier.Part -> "Part"
            ChapterMarkerTier.Book -> "Book"
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
        Field(
            label = "Start time",
            value = text,
            onInput = { text = it },
            id = "ced-chapter-time",
            errorText = "Type a time like 1:02:03.4.".takeIf { parsed == null },
        )
        DialogActions(
            confirmLabel = "Set",
            onConfirm = { parsed?.let(onConfirm) },
            onDismiss = onDismiss,
            confirmEnabled = parsed != null,
        )
    }
}
