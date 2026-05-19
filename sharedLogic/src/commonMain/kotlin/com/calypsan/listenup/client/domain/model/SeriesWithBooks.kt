package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a series with all its books.
 *
 * Used for library series views and series detail pages.
 * Includes sequence information for proper ordering.
 *
 * Books are projected as [BookListItem] — list-shaped, no detail-only fields.
 */
data class SeriesWithBooks(
    val series: Series,
    val books: List<BookListItem>,
    /** Maps bookId to sequence string (e.g., "1", "1.5") */
    val bookSequences: Map<String, String?>,
) {
    /**
     * Get the sequence for a specific book.
     */
    fun sequenceFor(bookId: String): String? = bookSequences[bookId]

    /**
     * Get books sorted by their sequence in this series.
     */
    fun booksSortedBySequence(): List<BookListItem> =
        books.sortedBy { book ->
            bookSequences[book.id.value]?.toFloatOrNull() ?: Float.MAX_VALUE
        }
}
