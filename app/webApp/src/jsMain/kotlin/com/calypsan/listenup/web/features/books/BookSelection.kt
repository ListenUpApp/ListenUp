package com.calypsan.listenup.web.features.books

/**
 * What a selectable book card needs to know, and null when the surface it sits on is not selectable.
 *
 * Mirrors iOS's `selection: BookSelectionObserver?` on `SelectableBookCard`: one nullable object
 * threaded to a card, rather than three parallel `selecting` / `selectedIds` / `onToggle` parameters
 * added to every card signature on every page.
 */
class BookSelection(
    val isSelecting: Boolean,
    val selectedIds: Set<String>,
    val onToggle: (String) -> Unit,
    val onStart: () -> Unit,
) {
    /** Whether [bookId] is currently picked. */
    fun isSelected(bookId: String): Boolean = bookId in selectedIds
}

/**
 * What a press on [bookId] should do.
 *
 * ⛔ While selecting, a press PICKS rather than opens. One gesture, two jobs, decided by the mode —
 * not a second target on every card, which is the same rule the library grid follows.
 *
 * A nullable receiver so a surface that is not selectable at all needs no branch of its own: no
 * selection means a press always opens.
 */
fun BookSelection?.press(
    bookId: String,
    onOpen: () -> Unit,
) {
    if (this != null && isSelecting) onToggle(bookId) else onOpen()
}
