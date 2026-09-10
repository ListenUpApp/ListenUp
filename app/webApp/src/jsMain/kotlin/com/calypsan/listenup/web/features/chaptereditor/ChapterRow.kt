package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One boundary, and everything that can be done to it without leaving the list.
 *
 * The list alone has to be sufficient — the spec calls the timeline "optional spatial sugar" — so
 * every operation the editor offers is reachable from a row: nudge either way, take the playhead,
 * pin against drift, rename, remove.
 *
 * The start time is rendered to the tenth of a second. A boundary is aimed at by ear, and a whole
 * second is wider than the gap the ear is judging.
 */
@Composable
internal fun ChapterRow(
    numbered: NumberedChapter,
    isSelected: Boolean,
    isLocked: Boolean,
    isPlaying: Boolean,
    playheadMs: Long?,
    onSelect: () -> Unit,
    onNudge: (Long) -> Unit,
    onSnapToPlayhead: () -> Unit,
    onToggleLock: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val chapter = numbered.chapter
    Div(attrs = {
        classes("chr")
        if (isSelected) classes("on")
        // The row is not a button: it holds six of them. Selection rides a click on the row's own
        // body, and the row announces the state a screen reader would otherwise have to infer.
        attr("aria-current", isSelected.toString())
        onClick { onSelect() }
    }) {
        Span(attrs = { classes("chr-n") }) { Text(numbered.number.toString()) }
        Div(attrs = { classes("chr-main") }) {
            Div(attrs = { classes("chr-t") }) { Text(chapter.title) }
            Div(attrs = { classes("chr-at") }) {
                Text(ChapterTimeFormat.precise(chapter.startTime))
                // "NOW", not a progress bar: this says which boundary the listener is inside, which
                // is the row worth aiming at, and nothing about how far through it they are.
                if (isPlaying) Span(attrs = { classes("chr-now") }) { Text("NOW") }
            }
        }
        Div(attrs = { classes("chr-acts") }) {
            RowAction(WebIcon.Minus, "Nudge back") { onNudge(-NUDGE_MS) }
            RowAction(WebIcon.Plus, "Nudge forward") { onNudge(NUDGE_MS) }
            // ⛔ Absent, not disabled, when there is no playhead for THIS book. A position borrowed
            // from a different book would write a number from somewhere else entirely.
            if (playheadMs != null) {
                RowAction(WebIcon.Target, "Set start at playhead", onClick = onSnapToPlayhead)
            }
            RowAction(
                icon = if (isLocked) WebIcon.Lock else WebIcon.Unlock,
                label = if (isLocked) "Unlock chapter" else "Lock chapter",
                pressed = isLocked,
                onClick = onToggleLock,
            )
            RowAction(WebIcon.Pencil, "Rename chapter", onClick = onRename)
            RowAction(WebIcon.Trash, "Delete chapter", onClick = onDelete)
        }
    }
}

/**
 * One icon-only control in a row.
 *
 * ⛔ `stopPropagation`: every one of these sits inside the row's own select handler, and without it
 * pressing Delete would also move the selection onto the row being deleted.
 */
@Composable
private fun RowAction(
    icon: WebIcon,
    label: String,
    pressed: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("chr-a")
        if (pressed == true) classes("on")
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        if (pressed != null) attr("aria-pressed", pressed.toString())
        disabledWhen(!enabled)
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) { Icon(icon, size = ACTION_ICON) }
}

/** Coarse ± step, the same one the native rows use. */
internal const val NUDGE_MS = 1_000L

private const val ACTION_ICON = 16
