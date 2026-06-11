package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.client.domain.model.ShelfBook

/**
 * Display-only sort options for a shelf's books, surfaced by the shelf-detail sort pill. [label] is
 * the pill's text. The default is [ADDED_NEWEST] — the shelf's natural order is added oldest-first,
 * so "newest" reverses it.
 */
enum class ShelfBookSort(
    val label: String,
) {
    ADDED_NEWEST("Added · Newest"),
    ADDED_OLDEST("Added · Oldest"),
    TITLE("Title · A–Z"),
    AUTHOR("Author · A–Z"),
}

/**
 * Returns [books] ordered for [sort]. Pure and display-only — the incoming list is the shelf's
 * natural added order (oldest first), so [ShelfBookSort.ADDED_OLDEST] is the identity. Title/author
 * sorts are case-insensitive; a book with no author sorts last under [ShelfBookSort.AUTHOR].
 */
fun sortShelfBooks(
    books: List<ShelfBook>,
    sort: ShelfBookSort,
): List<ShelfBook> =
    when (sort) {
        ShelfBookSort.ADDED_OLDEST -> books
        ShelfBookSort.ADDED_NEWEST -> books.reversed()
        ShelfBookSort.TITLE -> books.sortedBy { it.title.lowercase() }
        ShelfBookSort.AUTHOR -> books.sortedWith(compareBy(nullsLast()) { it.authorNames.firstOrNull()?.lowercase() })
    }
