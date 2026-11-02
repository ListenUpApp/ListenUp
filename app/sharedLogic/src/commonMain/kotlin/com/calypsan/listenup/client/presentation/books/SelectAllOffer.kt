package com.calypsan.listenup.client.presentation.books

/** What a selection bar offers beside the picks: take every book on screen, or put them all back. */
sealed interface SelectAllOffer {
    /**
     * "Select all N": not every book on screen is picked yet.
     *
     * @property count how many books are on screen, which is what selecting all takes.
     */
    data class SelectAll(
        val count: Int,
    ) : SelectAllOffer

    /** "Deselect all": every book on screen is picked. It clears the picks and stays in selection. */
    data object DeselectAll : SelectAllOffer
}

/**
 * The one offer a selection bar makes over the books on screen, the same on every platform: "Select all N" until
 * every one of them is picked, then "Deselect all" (the standard pairing). Null when the screen offers no books to
 * take. Picks that are off screen (a filter narrowed the grid) do not count for or against.
 *
 * @param onScreen the ids of the books the screen shows, as filtered.
 * @param selected the ids picked so far.
 */
fun selectAllOffer(
    onScreen: List<String>,
    selected: Set<String>,
): SelectAllOffer? =
    when {
        onScreen.isEmpty() -> null
        selected.containsAll(onScreen) -> SelectAllOffer.DeselectAll
        else -> SelectAllOffer.SelectAll(onScreen.size)
    }
