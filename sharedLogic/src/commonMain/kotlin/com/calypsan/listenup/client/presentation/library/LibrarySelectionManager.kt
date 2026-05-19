package com.calypsan.listenup.client.presentation.library

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val logger = KotlinLogging.logger {}

/**
 * Shared selection state for the Library screen.
 *
 * Not a ViewModel - a lightweight state holder injected as a singleton.
 * Both [LibraryViewModel] and [LibraryActionsViewModel] observe and
 * mutate this shared state for coordinated selection behavior.
 *
 * Scoped as singleton so selection persists if user navigates away
 * and returns to Library (until explicit exit or successful action).
 */
class LibrarySelectionManager {
    /**
     * Current selection mode state.
     * Observed by UI and both ViewModels.
     */
    val selectionMode: StateFlow<SelectionMode>
        field = MutableStateFlow<SelectionMode>(SelectionMode.None)

    /**
     * Enter selection mode with the given book as the initial selection.
     *
     * @param initialBookId The ID of the book that was long-pressed
     */
    fun enterSelectionMode(initialBookId: String) {
        selectionMode.value = SelectionMode.Active(selectedIds = setOf(initialBookId))
        logger.debug { "Entered selection mode with book: $initialBookId" }
    }

    /**
     * Toggle the selection state of a book.
     * If the book is selected, it will be deselected and vice versa.
     * If no books remain selected after toggle, exits selection mode.
     *
     * @param bookId The ID of the book to toggle
     */
    fun toggleSelection(bookId: String) {
        val current = selectionMode.value
        if (current !is SelectionMode.Active) return

        val newSelectedIds =
            if (bookId in current.selectedIds) {
                current.selectedIds - bookId
            } else {
                current.selectedIds + bookId
            }

        // If no books remain selected, exit selection mode
        selectionMode.value =
            if (newSelectedIds.isEmpty()) {
                logger.debug { "No books selected, exiting selection mode" }
                SelectionMode.None
            } else {
                SelectionMode.Active(selectedIds = newSelectedIds)
            }
    }

    /**
     * Exit selection mode and clear all selections.
     */
    fun exitSelectionMode() {
        selectionMode.value = SelectionMode.None
        logger.debug { "Exited selection mode" }
    }

    /**
     * Clear selection after a successful action (add to collection/shelf).
     * Semantically identical to [exitSelectionMode] but named for clarity at call sites.
     */
    fun clearAfterAction() {
        selectionMode.value = SelectionMode.None
        logger.debug { "Cleared selection after action" }
    }

    /**
     * Get the currently selected book IDs, or empty set if not in selection mode.
     */
    fun getSelectedIds(): Set<String> =
        when (val mode = selectionMode.value) {
            is SelectionMode.None -> emptySet()
            is SelectionMode.Active -> mode.selectedIds
        }
}
