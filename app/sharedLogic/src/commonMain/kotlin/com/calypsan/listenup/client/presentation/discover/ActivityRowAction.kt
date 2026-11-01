package com.calypsan.listenup.client.presentation.discover

/** One thing an activity row can be asked to do beyond its tap (spec §3.5 row actions). */
sealed interface ActivityRowAction {
    /** Open the book the row is about. */
    data class OpenBook(
        val bookId: String,
    ) : ActivityRowAction

    /** Open the shelf the row is about. */
    data class OpenShelf(
        val shelfId: String,
    ) : ActivityRowAction

    /** Open the person who did it. */
    data class OpenProfile(
        val userId: String,
    ) : ActivityRowAction

    /** Put the row's book on one of your shelves. */
    data class AddToShelf(
        val bookId: String,
    ) : ActivityRowAction
}

/**
 * The actions a row offers, in the board's order, decided once for every platform. A book row: the book, the
 * person, add to a shelf. A shelf row: the shelf and the person. A row about neither (a join, a milestone) offers
 * nothing, because its tap already opens the person (plan Q5). The menu, the screen readers' custom actions and the
 * Menu key all read this one list (plan C7).
 *
 * @param canShelve whether the screen can shelve one book from a row. False where there is no selection to shelve
 *   through, and while a selection is under way: shelving one book replaces the selection and ends it, which would
 *   wipe the reader's picks.
 */
fun activityRowActions(
    row: ActivityUiModel,
    canShelve: Boolean,
): List<ActivityRowAction> {
    val bookId = row.bookId
    val shelfId = row.shelfId
    return when {
        bookId != null -> {
            buildList {
                add(ActivityRowAction.OpenBook(bookId))
                add(ActivityRowAction.OpenProfile(row.userId))
                if (canShelve) add(ActivityRowAction.AddToShelf(bookId))
            }
        }

        shelfId != null -> {
            listOf(ActivityRowAction.OpenShelf(shelfId), ActivityRowAction.OpenProfile(row.userId))
        }

        else -> {
            emptyList()
        }
    }
}
