package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The chapter editor — every boundary in a book, and everything that can be done to one.
 *
 * Pure in [state]; the store wiring lives one level up. The editing itself lives in the shared
 * `ChapterEditorViewModel`, which is the whole reason this page can exist without reimplementing
 * drift correction, validation, or the draft.
 *
 * **This is the list, not the timeline.** The native editor pairs the list with a minimap and a
 * detail lane; the spec calls those "optional spatial sugar" and requires the list alone to be
 * sufficient. So every operation is reachable from a row, and the lane is not part of this page.
 *
 * Two things it is careful about. The playhead is only offered when the book being edited is the
 * one actually loaded in the player — a position borrowed from another book would write a number
 * from somewhere else entirely. And a dirty draft is never dropped silently: this page holds the
 * only copy of the reader's work until they save.
 */
@Composable
fun ChapterEditorPage(
    state: ChapterEditorUiState,
    playheadMs: Long?,
    onSelect: (String?) -> Unit,
    onNudge: (String, Long) -> Unit,
    onSnapToPlayhead: (String, Long) -> Unit,
    onRetitle: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAt: (Long, String) -> Unit,
    onToggleLock: (String) -> Unit,
    onBeginDrift: () -> Unit,
    onPinAnchor: (String, Long) -> Unit,
    onApplyDrift: () -> Unit,
    onCancelDrift: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    problem: String? = null,
) {
    Div(attrs = { classes("ched") }) {
        when (state) {
            ChapterEditorUiState.Loading -> {
                Div(attrs = { classes("skel", "ched-skel") })
            }

            is ChapterEditorUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H1 { Text("These chapters can't be shown") }
                    P { Text(state.message) }
                    Button(attrs = {
                        classes("btn-o")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        onClick { onLeave() }
                    }) { Text("Back to the book") }
                }
            }

            is ChapterEditorUiState.Editing -> {
                EditingContent(
                    state = state,
                    playheadMs = playheadMs,
                    onSelect = onSelect,
                    onNudge = onNudge,
                    onSnapToPlayhead = onSnapToPlayhead,
                    onRetitle = onRetitle,
                    onRemove = onRemove,
                    onAddAt = onAddAt,
                    onToggleLock = onToggleLock,
                    onBeginDrift = onBeginDrift,
                    onPinAnchor = onPinAnchor,
                    onApplyDrift = onApplyDrift,
                    onCancelDrift = onCancelDrift,
                    onUndo = onUndo,
                    onSave = onSave,
                    onLeave = onLeave,
                    problem = problem,
                )
            }
        }
    }
}

@Composable
private fun EditingContent(
    state: ChapterEditorUiState.Editing,
    playheadMs: Long?,
    onSelect: (String?) -> Unit,
    onNudge: (String, Long) -> Unit,
    onSnapToPlayhead: (String, Long) -> Unit,
    onRetitle: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAt: (Long, String) -> Unit,
    onToggleLock: (String) -> Unit,
    onBeginDrift: () -> Unit,
    onPinAnchor: (String, Long) -> Unit,
    onApplyDrift: () -> Unit,
    onCancelDrift: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    problem: String?,
) {
    var query by remember { mutableStateOf("") }
    var rowAction by remember { mutableStateOf<RowAction?>(null) }

    EditorHeader(state, onUndo, onBeginDrift, onSave, onLeave)

    if (state.changedElsewhere) {
        Div(attrs = {
            classes("ched-elsewhere")
            attr("role", "status")
            attr("aria-live", "polite")
        }) {
            Span(attrs = { classes("ched-elsewhere-t") }) { Text("Chapters changed on another device") }
            Span(attrs = { classes("ched-elsewhere-b") }) { Text("Your edits are kept. Review before saving.") }
        }
    }

    // ⛔ `role="alert"`, not a passing toast. A refused save means nothing left the device and the
    // reader has to fix a specific row — a message that disappears on its own takes the row number
    // with it.
    problem?.let {
        P(attrs = {
            classes("ched-problem")
            attr("role", "alert")
        }) { Text(it) }
    }

    state.drift?.let { drift ->
        DriftPanel(
            drift = drift,
            chapters = state.chapters,
            selectedChapterId = state.selectedChapterId,
            playheadMs = playheadMs,
            onPin = {
                val selected = state.selectedChapterId
                if (selected != null && playheadMs != null) onPinAnchor(selected, playheadMs)
            },
            onApply = onApplyDrift,
            onCancel = onCancelDrift,
        )
    }

    if (state.isEmpty) {
        EmptyState(playheadMs, onAddAt)
        return
    }

    // Numbered against the FULL set, then filtered — see [numbered].
    val all = state.chapters.numbered()
    val shown = if (query.isBlank()) all else all.filter { it.chapter.title.contains(query, ignoreCase = true) }

    Div(attrs = { classes("ched-tools") }) {
        Field(
            label = "Jump to title…",
            value = query,
            onInput = { query = it },
            leading = WebIcon.Search,
            id = "ched-search",
        )
        if (playheadMs != null) {
            Button(attrs = {
                classes(BTN_SECONDARY, "ched-add")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onAddAt(playheadMs, NEW_CHAPTER_TITLE) }
            }) {
                Icon(WebIcon.Plus, size = SMALL_ICON)
                Text("Add chapter at playhead")
            }
        }
    }

    if (shown.isEmpty()) {
        P(attrs = { classes("ched-none") }) { Text("No chapters match “$query”.") }
    } else {
        Div(attrs = {
            classes("ched-list")
            attr("role", "list")
        }) {
            shown.forEach { numbered ->
                ChapterRow(
                    numbered = numbered,
                    isSelected = numbered.chapter.id == state.selectedChapterId,
                    isLocked = numbered.chapter.id in state.lockedChapterIds,
                    isPlaying = playheadMs != null && numbered.chapter.holds(playheadMs),
                    playheadMs = playheadMs,
                    onSelect = { onSelect(numbered.chapter.id) },
                    onNudge = { delta -> onNudge(numbered.chapter.id, delta) },
                    onSnapToPlayhead = { playheadMs?.let { onSnapToPlayhead(numbered.chapter.id, it) } },
                    onToggleLock = { onToggleLock(numbered.chapter.id) },
                    onRename = { rowAction = RowAction.Renaming(numbered.chapter.id) },
                    onDelete = { rowAction = RowAction.Deleting(numbered.chapter.id) },
                )
            }
        }
    }

    when (val action = rowAction) {
        null -> {}

        is RowAction.Renaming -> {
            RenameChapterDialog(
                initialTitle =
                    state.chapters
                        .firstOrNull { it.id == action.chapterId }
                        ?.title
                        .orEmpty(),
                onConfirm = {
                    onRetitle(action.chapterId, it)
                    rowAction = null
                },
                onDismiss = { rowAction = null },
            )
        }

        is RowAction.Deleting -> {
            DeleteChapterDialog(
                onConfirm = {
                    onRemove(action.chapterId)
                    rowAction = null
                },
                onDismiss = { rowAction = null },
            )
        }
    }
}

/** The book, what state the draft is in, and the four things that act on the whole set. */
@Composable
private fun EditorHeader(
    state: ChapterEditorUiState.Editing,
    onUndo: () -> Unit,
    onBeginDrift: () -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
) {
    Div(attrs = { classes("ched-head") }) {
        Div(attrs = { classes("ched-titles") }) {
            H1(attrs = { classes("ched-t") }) { Text("Edit chapters") }
            Div(attrs = { classes("ched-sub") }) {
                Text("${state.bookTitle} · ${state.chapters.size} chapters")
                // Unsaved and Saving are the same slot: they are the same fact at two moments, and
                // showing both at once would say the draft is unsaved and being saved.
                if (state.isSaving) {
                    Span(attrs = { classes("ched-status") }) { Text("Saving…") }
                } else if (state.isDirty) {
                    Span(attrs = { classes("ched-status", "on") }) { Text("Unsaved") }
                }
            }
        }
        Div(attrs = { classes("ched-acts") }) {
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onLeave() }
            }) { Text("Back") }
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                // Nothing to interpolate between on an empty or single-chapter book.
                disabledWhen(state.chapters.size <= 1 || state.drift != null)
                onClick { onBeginDrift() }
            }) { Text("Fix drift") }
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                disabledWhen(!state.canUndo)
                onClick { onUndo() }
            }) { Text("Undo") }
            Button(attrs = {
                classes("btn-c")
                attr(ATTR_TYPE, VALUE_BUTTON)
                disabledWhen(state.isSaving || !state.isDirty)
                onClick { onSave() }
            }) { Text("Save chapters") }
        }
    }
}

/**
 * A book that was never chaptered.
 *
 * "Never stranded": the way out is a boundary the reader can add by hand, right here, rather than
 * a dead end that waits for a scraper to do better next time.
 */
@Composable
private fun EmptyState(
    playheadMs: Long?,
    onAddAt: (Long, String) -> Unit,
) {
    Div(attrs = { classes("ched-empty") }) {
        H2 { Text("No chapters yet") }
        P { Text("This book was never chaptered. Play it to where a chapter starts, then add your first boundary.") }
        if (playheadMs != null) {
            Button(attrs = {
                classes("btn-c")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onAddAt(playheadMs, NEW_CHAPTER_TITLE) }
            }) { Text("Add first chapter at playhead") }
        } else {
            P(attrs = { classes("ched-none") }) { Text("Play this book to place the first boundary.") }
        }
    }
}

/** Whether [at] falls inside this chapter — which boundary the listener is currently inside. */
private fun Chapter.holds(at: Long): Boolean = at >= startTime && at < startTime + duration

internal const val NEW_CHAPTER_TITLE = "New chapter"

private const val ATTR_TYPE = "type"

private const val BTN_SECONDARY = "btn-o"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
