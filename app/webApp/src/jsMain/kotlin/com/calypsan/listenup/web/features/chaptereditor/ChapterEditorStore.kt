package com.calypsan.listenup.web.features.chaptereditor

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Chapter Editor session.
 *
 * Wider than the other edit sessions because this ViewModel takes methods rather than one sealed
 * event — the editing vocabulary IS the API, and wrapping it in a web-only event type would be a
 * second vocabulary for the same operations. The names are the ViewModel's, unchanged.
 */
@Suppress("LongParameterList")
class ChapterEditorSession(
    val state: StateFlow<ChapterEditorUiState>,
    val events: Flow<ChapterEditorEvent>,
    val onSelect: (String?) -> Unit,
    val onNudge: (String, Long) -> Unit,
    val onSnapToPlayhead: (String, Long) -> Unit,
    val onRetitle: (String, String) -> Unit,
    val onRemove: (String) -> Unit,
    val onAddAt: (Long, String) -> Unit,
    val onToggleLock: (String) -> Unit,
    val onBeginDrift: () -> Unit,
    val onPinAnchor: (String, Long) -> Unit,
    val onApplyDrift: () -> Unit,
    val onCancelDrift: () -> Unit,
    val onUndo: () -> Unit,
    val onResetToSource: () -> Unit,
    val onSave: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenChapterEditor = (bookId: String) -> ChapterEditorSession

/**
 * The production source: the shared [ChapterEditorViewModel], scoped to [bookId].
 *
 * ⛔ [ChapterEditorViewModel.close] is what the session's `close` reaches, via the store. The
 * ViewModel's own KDoc names this as web's responsibility: without it the `viewModelScope` outlives
 * the page and an in-flight save orphans.
 */
fun graphChapterEditor(koin: Koin): OpenChapterEditor =
    { bookId ->
        val viewModel = koin.get<ChapterEditorViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        ChapterEditorSession(
            state = viewModel.state,
            events = viewModel.events,
            onSelect = viewModel::select,
            onNudge = viewModel::nudge,
            onSnapToPlayhead = viewModel::snapToPlayhead,
            onRetitle = viewModel::retitle,
            onRemove = viewModel::remove,
            onAddAt = viewModel::addAt,
            onToggleLock = viewModel::toggleLock,
            onBeginDrift = viewModel::beginDrift,
            onPinAnchor = viewModel::pinAnchor,
            onApplyDrift = viewModel::applyDrift,
            onCancelDrift = viewModel::cancelDrift,
            onUndo = viewModel::undo,
            onResetToSource = viewModel::resetToSource,
            onSave = viewModel::save,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedChapterEditor(
    state: ChapterEditorUiState,
    events: Flow<ChapterEditorEvent> = emptyFlow(),
    onSelect: (String?) -> Unit = {},
    onNudge: (String, Long) -> Unit = { _, _ -> },
    onSnapToPlayhead: (String, Long) -> Unit = { _, _ -> },
    onRetitle: (String, String) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit = {},
    onAddAt: (Long, String) -> Unit = { _, _ -> },
    onToggleLock: (String) -> Unit = {},
    onBeginDrift: () -> Unit = {},
    onPinAnchor: (String, Long) -> Unit = { _, _ -> },
    onApplyDrift: () -> Unit = {},
    onCancelDrift: () -> Unit = {},
    onUndo: () -> Unit = {},
    onResetToSource: () -> Unit = {},
    onSave: () -> Unit = {},
): OpenChapterEditor =
    {
        ChapterEditorSession(
            state = MutableStateFlow(state),
            events = events,
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
            onResetToSource = onResetToSource,
            onSave = onSave,
            close = {},
        )
    }

/**
 * A chapter paired with the number it carries in the whole book.
 *
 * ⛔ The pairing exists so filtering cannot renumber anything. Search narrows what is shown, and
 * numbering the *visible* rows would relabel chapter 213 as chapter 1 the moment someone typed —
 * so the number travels with the chapter. Number the full set, then filter the result.
 */
class NumberedChapter(
    val chapter: Chapter,
    val number: Int,
)

/** Pairs every chapter with its true number, before any filtering happens. */
internal fun List<Chapter>.numbered(): List<NumberedChapter> = mapIndexed { i, c -> NumberedChapter(c, i + 1) }
