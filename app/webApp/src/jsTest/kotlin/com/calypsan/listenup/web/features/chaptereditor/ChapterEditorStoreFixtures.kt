package com.calypsan.listenup.web.features.chaptereditor

import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Builds [ChapterMarkerCalls] with no-op defaults for specs. */
fun chapterMarkerCalls(
    onStart: (String, ChapterMarkerTier, String) -> Unit = { _, _, _ -> },
    onSave: (String, ChapterMarkerTier, String, String) -> Unit = { _, _, _, _ -> },
    onRemove: (String, ChapterMarkerTier) -> Unit = { _, _ -> },
    onAcceptSuggestion: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
): ChapterMarkerCalls =
    ChapterMarkerCalls(
        onStart = onStart,
        onSave = onSave,
        onRemove = onRemove,
        onAcceptSuggestion = onAcceptSuggestion,
        onDismissSuggestion = onDismissSuggestion,
    )

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
    onRetime: (String, Long) -> Unit = { _, _ -> },
    onInsertBelow: (String, String) -> Unit = { _, _ -> },
    onPlayFrom: (String) -> Unit = {},
    onToggleLock: (String) -> Unit = {},
    onBeginDrift: () -> Unit = {},
    onPinAnchor: (String, Long) -> Unit = { _, _ -> },
    onApplyDrift: () -> Unit = {},
    onCancelDrift: () -> Unit = {},
    onUndo: () -> Unit = {},
    onResetToSource: () -> Unit = {},
    onSave: () -> Unit = {},
    markers: ChapterMarkerCalls = chapterMarkerCalls(),
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
            onRetime = onRetime,
            onInsertBelow = onInsertBelow,
            onPlayFrom = onPlayFrom,
            onToggleLock = onToggleLock,
            onBeginDrift = onBeginDrift,
            onPinAnchor = onPinAnchor,
            onApplyDrift = onApplyDrift,
            onCancelDrift = onCancelDrift,
            onUndo = onUndo,
            onResetToSource = onResetToSource,
            onSave = onSave,
            markers = markers,
            close = {},
        )
    }
