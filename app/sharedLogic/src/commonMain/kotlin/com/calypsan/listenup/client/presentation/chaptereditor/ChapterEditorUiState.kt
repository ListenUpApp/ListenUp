package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * What the chapter editor is showing.
 *
 * A sealed hierarchy rather than one class with nullable fields, so the states that cannot coexist
 * cannot be constructed: there is no "loaded but with an error and no chapters".
 */
sealed interface ChapterEditorUiState {
    /** Before the first emission from the local mirror. */
    data object Loading : ChapterEditorUiState

    /**
     * The editable state.
     *
     * @property bookTitle for the header.
     * @property chapters the working set — the draft when one exists, otherwise what the local
     *   mirror holds.
     * @property bookDurationMs total audio length, taken from the book rather than from the last
     *   chapter's end. Those differ when a chapter set does not reach the end of the file, and
     *   deriving it from the chapters would silently shorten the book every time it happened.
     * @property selectedChapterId the boundary the list and the lane are both focused on.
     * @property isDirty there are unsaved edits.
     * @property canUndo there is a snapshot to step back to.
     * @property isSaving a save is in flight.
     * @property changedElsewhere the set changed on another device while this draft was open.
     *   Derived, never latched: it is true exactly while the draft's fork point disagrees with what
     *   the mirror now holds, so it clears itself the moment the user saves or resets.
     */
    data class Editing(
        val bookTitle: String,
        val chapters: List<Chapter>,
        val bookDurationMs: Long,
        val selectedChapterId: String? = null,
        val isDirty: Boolean = false,
        val canUndo: Boolean = false,
        val isSaving: Boolean = false,
        val changedElsewhere: Boolean = false,
    ) : ChapterEditorUiState {
        /** True when the book has no chapters at all — the "never stranded" empty state. */
        val isEmpty: Boolean get() = chapters.isEmpty()
    }

    /** The book could not be loaded. */
    data class Error(
        val message: String,
    ) : ChapterEditorUiState
}

/** One-shot outcomes the screen reacts to once, rather than rendering from state. */
sealed interface ChapterEditorEvent {
    /** The chapter set reached the server. */
    data object Saved : ChapterEditorEvent

    /** The save was refused. The draft is untouched and still editable. */
    data class SaveFailed(
        val error: AppError,
    ) : ChapterEditorEvent
}
