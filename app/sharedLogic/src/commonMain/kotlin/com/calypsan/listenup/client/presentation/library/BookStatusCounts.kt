package com.calypsan.listenup.client.presentation.library

/**
 * How many books are in each reading state, over the WHOLE library, whatever filter is active.
 *
 * A data class rather than a `Map<BookStatusFilter, Int>`: a bridged map keyed by an enum traps on
 * iOS (`NoBridgedEnumCollectionsInUiStateRule`).
 */
data class BookStatusCounts(
    val all: Int,
    val inProgress: Int,
    val notStarted: Int,
    val finished: Int,
) {
    /** The count a chip or menu row for [filter] shows. */
    fun countFor(filter: BookStatusFilter): Int =
        when (filter) {
            BookStatusFilter.ALL -> all
            BookStatusFilter.IN_PROGRESS -> inProgress
            BookStatusFilter.NOT_STARTED -> notStarted
            BookStatusFilter.FINISHED -> finished
        }
}
