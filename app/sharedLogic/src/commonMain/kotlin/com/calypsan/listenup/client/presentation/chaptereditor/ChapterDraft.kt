package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The chapter set as the user has it right now, and what it was forked from.
 *
 * The editor never writes an edit straight through to Room. It accumulates here until a save,
 * which is what makes undo possible and what lets a sync frame arriving mid-edit be *reported*
 * rather than silently applied over someone's work.
 *
 * [forkedFrom] is the confirmed set this draft started from. Keeping it is what turns
 * "changed elsewhere" into a derived fact — compare it against what the repository is currently
 * emitting — instead of a latched boolean that has to be set from the right place and cleared from
 * three others.
 *
 * @property chapters the working set, always ordered by start time.
 * @property forkedFrom the server-confirmed set this was forked from.
 * @property undo snapshots to walk back through, oldest first. Empty when nothing has been edited.
 */
data class ChapterDraft(
    val chapters: List<Chapter>,
    val forkedFrom: List<Chapter>,
    val undo: List<List<Chapter>> = emptyList(),
) {
    /** True when the working set differs from what it was forked from. */
    val isDirty: Boolean get() = chapters != forkedFrom

    /** True when there is a snapshot to walk back to. */
    val canUndo: Boolean get() = undo.isNotEmpty()

    /**
     * Records [next] as the new working set, pushing the current one onto [undo].
     *
     * A transform returning the set unchanged pushes nothing: a nudge that got clamped at a
     * neighbour is not an edit, and burying real edits under no-op undo frames would make the
     * button lie about what it does.
     */
    fun mutate(next: List<Chapter>): ChapterDraft =
        if (next == chapters) this else copy(chapters = next, undo = undo + listOf(chapters))

    /** Steps back one snapshot. A no-op when there is nothing to undo. */
    fun undone(): ChapterDraft = if (undo.isEmpty()) this else copy(chapters = undo.last(), undo = undo.dropLast(1))

    /** Throws every local edit away and returns to [forkedFrom]. */
    fun reset(): ChapterDraft = copy(chapters = forkedFrom, undo = emptyList())
}
