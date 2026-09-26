package com.calypsan.listenup.web.features.chaptereditor

import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.Event
import org.w3c.dom.HTMLElement
import kotlinx.browser.window
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineChapter
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
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
    onRetime: (String, Long) -> Unit,
    onInsertBelow: (String, String) -> Unit,
    onPlayFrom: (String) -> Unit,
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
                    onRetime = onRetime,
                    onInsertBelow = onInsertBelow,
                    onPlayFrom = onPlayFrom,
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
    onRetime: (String, Long) -> Unit,
    onInsertBelow: (String, String) -> Unit,
    onPlayFrom: (String) -> Unit,
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
    var lane by remember { mutableStateOf<TimelineLane?>(null) }

    EditorKeys(
        chapters = state.chapters,
        selectedChapterId = state.selectedChapterId,
        // A dialog owns the keyboard while it is open: arrows move its caret, not a boundary.
        enabled = rowAction == null,
        onNudge = onNudge,
        onSelect = onSelect,
    )

    EditorHeader(state, onUndo, onBeginDrift, onSave, onLeave)

    EditorNotices(changedElsewhere = state.changedElsewhere, problem = problem)

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
    val shown = all.matching(query)

    Div(attrs = { classes("ched-body") }) {
        ChapterTimeline(
            state = state,
            // Opened around the playhead when this book is playing, else at the start.
            lane = lane ?: TimelineLane.opening(state.bookDurationMs, playheadMs, widthPx = 0f),
            onLaneChange = { lane = it },
            playheadMs = playheadMs,
            ghosts = driftGhosts(state),
            onRetime = onRetime,
        )
        Div(attrs = { classes("ched-listpane") }) {
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
                            onEditTime = { rowAction = RowAction.EditingTime(numbered.chapter.id) },
                            onInsertBelow = { onInsertBelow(numbered.chapter.id, NEW_CHAPTER_TITLE) },
                            onPlayFrom = { onPlayFrom(numbered.chapter.id) },
                            onRename = { rowAction = RowAction.Renaming(numbered.chapter.id) },
                            onDelete = { rowAction = RowAction.Deleting(numbered.chapter.id) },
                        )
                    }
                }
            }
        }
    }

    RowActionDialogs(
        action = rowAction,
        chapters = state.chapters,
        onAction = { rowAction = it },
        onRetitle = onRetitle,
        onRetime = onRetime,
        onRemove = onRemove,
    )
}

/** The two things said above the list: the set changed elsewhere, and a refused save. */
@Composable
private fun EditorNotices(
    changedElsewhere: Boolean,
    problem: String?,
) {
    if (changedElsewhere) {
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
}

/**
 * A row's dialogs, one at a time — [action] is a single nullable value so "renaming and deleting at
 * once" cannot be reached. [onAction] closes (null) or moves between them.
 */
@Composable
private fun RowActionDialogs(
    action: RowAction?,
    chapters: List<Chapter>,
    onAction: (RowAction?) -> Unit,
    onRetitle: (String, String) -> Unit,
    onRetime: (String, Long) -> Unit,
    onRemove: (String) -> Unit,
) {
    when (action) {
        null -> {}

        is RowAction.Renaming -> {
            RenameChapterDialog(
                initialTitle =
                    chapters
                        .firstOrNull { it.id == action.chapterId }
                        ?.title
                        .orEmpty(),
                onConfirm = {
                    onRetitle(action.chapterId, it)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
            )
        }

        is RowAction.EditingTime -> {
            ChapterTimeDialog(
                initialMs = chapters.firstOrNull { it.id == action.chapterId }?.startTime ?: 0L,
                onConfirm = {
                    onRetime(action.chapterId, it)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
            )
        }

        is RowAction.Deleting -> {
            DeleteChapterDialog(
                onConfirm = {
                    onRemove(action.chapterId)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
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

/**
 * The drift preview's corrected positions as lane markers, or none while there is no proposal to
 * show. Numbered by position in the corrected set, so a ghost carries the number it would have.
 */
private fun driftGhosts(state: ChapterEditorUiState.Editing): List<TimelineChapter> {
    val ready = state.drift?.preview as? DriftPreview.Ready ?: return emptyList()
    return ready.corrected.mapIndexed { index, chapter ->
        TimelineChapter(id = chapter.id, number = index + 1, startMs = chapter.startTime)
    }
}

/** A fine nudge — Shift with an arrow key — a tenth of the coarse step (spec §7.8). */
private const val FINE_NUDGE_MS = 100L

/**
 * The editor's keyboard (spec §7.8): ← / → nudge the selected boundary by a second, a tenth with
 * Shift; `[` / `]` step the selection to the previous or next chapter.
 *
 * ⛔ Ignored while focus is in a text field — typing a title must never move a boundary — and while
 * [enabled] is false because a dialog has the keyboard.
 */
@Composable
private fun EditorKeys(
    chapters: List<Chapter>,
    selectedChapterId: String?,
    enabled: Boolean,
    onNudge: (String, Long) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val latest = rememberUpdatedState(KeyTargets(chapters, selectedChapterId, enabled, onNudge, onSelect))
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { event -> latest.value.handle(event as KeyboardEvent) }
        window.addEventListener("keydown", listener)
        onDispose { window.removeEventListener("keydown", listener) }
    }
}

/** What a key press acts on, read fresh for every press. */
private class KeyTargets(
    val chapters: List<Chapter>,
    val selectedChapterId: String?,
    val enabled: Boolean,
    val onNudge: (String, Long) -> Unit,
    val onSelect: (String?) -> Unit,
) {
    fun handle(event: KeyboardEvent) {
        if (!enabled || event.isTyping()) return
        val selected = selectedChapterId
        val index = chapters.indexOfFirst { it.id == selected }
        val step = if (event.shiftKey) FINE_NUDGE_MS else NUDGE_MS
        when (event.key) {
            "ArrowLeft", "ArrowRight" -> {
                if (selected == null) return
                onNudge(selected, if (event.key == "ArrowLeft") -step else step)
                event.preventDefault()
            }

            "]" -> {
                chapters.getOrNull(if (index < 0) 0 else index + 1)?.let { onSelect(it.id) }
            }

            "[" -> {
                chapters.getOrNull(if (index < 0) chapters.lastIndex else index - 1)?.let { onSelect(it.id) }
            }
        }
    }

    private fun KeyboardEvent.isTyping(): Boolean {
        val element = target as? HTMLElement ?: return false
        return element.tagName in TYPING_TAGS || element.isContentEditable
    }
}

private val TYPING_TAGS = setOf("INPUT", "TEXTAREA", "SELECT")
