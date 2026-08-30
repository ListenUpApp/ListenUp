package com.calypsan.listenup.client.presentation.bulkedit

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit

/**
 * What the bulk edit screen shows.
 *
 * A sealed hierarchy rather than one class with nullable fields, so states that cannot coexist
 * cannot be represented.
 */
sealed interface BulkEditUiState {
    /** The selected books are still loading. */
    data object Loading : BulkEditUiState

    /**
     * The form.
     *
     * @property bookCount how many books were actually loaded — the number Apply will change,
     *   and the number on the Apply button.
     * @property requestedCount how many books the user selected. Equal to [bookCount] in the
     *   normal case; larger when a selected book could not be loaded, which happens when one was
     *   deleted from another device between the grid and this screen. The screen shows the
     *   difference rather than quietly editing fewer books than were chosen.
     * @property edits the instructions built so far. Empty means nothing has been touched.
     * @property preview per-instruction counts of books that would actually change.
     * @property changedBookCount how many books would change at least one field — the number on
     *   the Apply button, and the number [BulkEditEvent.Applied] will report. Not the size of the
     *   selection: promising to change 40 books and then reporting 12 updated is the same
     *   overstatement the preview exists to prevent. Not the sum of [preview] counts either,
     *   which would double-count a book two instructions both touch.
     * @property isApplying an apply is in flight.
     * @property sharedPublisher the publisher every selected book already agrees on, or null when
     *   they differ — shown as placeholder text, never as a value.
     * @property sharedPublishYear as [sharedPublisher], for the publication year.
     * @property sharedLanguage as [sharedPublisher], for the language.
     */
    data class Editing(
        val bookCount: Int,
        val requestedCount: Int = bookCount,
        val edits: List<BulkEdit> = emptyList(),
        val preview: List<BulkEditPreviewRow> = emptyList(),
        val changedBookCount: Int = 0,
        val isApplying: Boolean = false,
        val sharedPublisher: String? = null,
        val sharedPublishYear: Int? = null,
        val sharedLanguage: String? = null,
    ) : BulkEditUiState {
        /** True when applying would change at least one book. */
        val canApply: Boolean get() = changedBookCount > 0
    }
}

/**
 * One line of the "what will this do" summary.
 *
 * The count excludes books the instruction would not change, because a bulk operation has no undo
 * and a number that includes untouched books overstates what Apply does.
 *
 * @property edit the instruction being described.
 * @property affectedCount how many of the selected books it would actually change.
 */
data class BulkEditPreviewRow(
    val edit: BulkEdit,
    val affectedCount: Int,
)

/** One-shot outcomes of applying, reacted to once rather than rendered from state. */
sealed interface BulkEditEvent {
    /**
     * Every book was updated.
     *
     * @property changedCount how many of them actually changed.
     */
    data class Applied(
        val changedCount: Int,
    ) : BulkEditEvent

    /**
     * Applying stopped at a failure. The books already committed stand — there is no rollback.
     *
     * @property error the typed reason the run stopped.
     * @property appliedCount how many books were committed before it did.
     */
    data class Failed(
        val error: AppError,
        val appliedCount: Int,
    ) : BulkEditEvent
}
