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
    /**
     * Shelve one book: select it alone, open the shelf picker, and end the selection when the picker
     * closes (plan C5). A row menu's "Add {book} to a shelf" — iOS's `addOneBookToShelf`.
     */
    val onShelveOne: (String) -> Unit = {},
    /** Add one book to a collection, by the same rule as [onShelveOne] (an admin's book menu). */
    val onCollectOne: (String) -> Unit = {},
    /** Start selecting with [String] picked: a book menu's Select. */
    val onStartWith: (String) -> Unit = {},
    /** Whether the reader is an admin: collections and editing are theirs. */
    val isAdmin: Boolean = false,
) {
    /**
     * [onShelveOne] for a row menu, or null while a selection is under way: shelving one book
     * replaces the selection and ends it when the picker closes, which would wipe the reader's picks.
     */
    val shelveOne: ((String) -> Unit)? get() = onShelveOne.takeUnless { isSelecting }

    /** [onCollectOne] for a book menu, or null while selecting ([shelveOne]'s rule) or for a member. */
    val collectOne: ((String) -> Unit)? get() = onCollectOne.takeIf { isAdmin && !isSelecting }

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
