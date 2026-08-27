package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.client.domain.model.ShelfBook

/**
 * Sort options for a shelf's books, surfaced by the shelf-detail sort pill. [label] is the pill's
 * text. The default is [ADDED_NEWEST] — the shelf's natural order is added oldest-first, so
 * "newest" reverses it.
 *
 * All but [MANUAL] are display-only: they reorder what you see and leave the shelf alone.
 */
enum class ShelfBookSort(
    val label: String,
) {
    ADDED_NEWEST("Added · Newest"),
    ADDED_OLDEST("Added · Oldest"),
    TITLE("Title · A–Z"),
    AUTHOR("Author · A–Z"),

    /**
     * The order the owner put the books in — the only mode in which dragging means anything.
     *
     * Every other option is a lens over the shelf; this one IS the shelf. Reordering under a lens
     * would either fight the sort (a dragged book snapping back to its alphabetical place) or
     * rewrite the stored order from a view that was never the stored order — a drag "up" under
     * [ADDED_NEWEST], which is reversed, moves a book *later*. So the drag handles appear here and
     * nowhere else, which also makes the pill the honest place to explain why.
     */
    MANUAL("Manual"),
}

/**
 * Returns [books] ordered for [sort]. Pure — the incoming list is the shelf's natural added order
 * (oldest first), so [ShelfBookSort.ADDED_OLDEST] and [ShelfBookSort.MANUAL] are both the identity.
 * They differ in what the screen allows, not in what this returns: manual order is the stored one,
 * which is why it is the mode a drag can write back to. Title/author
 * sorts are case-insensitive; a book with no author sorts last under [ShelfBookSort.AUTHOR].
 */
fun sortShelfBooks(
    books: List<ShelfBook>,
    sort: ShelfBookSort,
): List<ShelfBook> =
    when (sort) {
        ShelfBookSort.ADDED_OLDEST, ShelfBookSort.MANUAL -> books
        ShelfBookSort.ADDED_NEWEST -> books.reversed()
        ShelfBookSort.TITLE -> books.sortedBy { it.title.lowercase() }
        ShelfBookSort.AUTHOR -> books.sortedWith(compareBy(nullsLast()) { it.authorNames.firstOrNull()?.lowercase() })
    }
