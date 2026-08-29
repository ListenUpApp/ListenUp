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
     * @property bookCount how many books are selected — the number on the Apply button.
     * @property edits the instructions built so far. Empty means nothing has been touched.
     * @property preview per-instruction counts of books that would actually change.
     * @property isApplying an apply is in flight.
     * @property sharedPublisher the publisher every selected book already agrees on, or null when
     *   they differ — shown as placeholder text, never as a value.
     * @property sharedPublishYear as [sharedPublisher], for the publication year.
     * @property sharedLanguage as [sharedPublisher], for the language.
     */
    data class Editing(
        val bookCount: Int,
        val edits: List<BulkEdit> = emptyList(),
        val preview: List<BulkEditPreviewRow> = emptyList(),
        val isApplying: Boolean = false,
        val sharedPublisher: String? = null,
        val sharedPublishYear: Int? = null,
        val sharedLanguage: String? = null,
    ) : BulkEditUiState {
        /** True when there is at least one instruction that would change at least one book. */
        val canApply: Boolean get() = preview.any { it.affectedCount > 0 }
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
