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
    /** Maps bookId to its position in this series (e.g. 1.0, 1.5); null when unnumbered. */
    val bookSequences: Map<String, Double?>,
    /**
     * How many series sit directly inside this one. Non-zero only on the Library's top-level cards
     * ([com.calypsan.listenup.client.domain.repository.SeriesRepository.observeRootSeriesWithBooks]),
     * whose [books] then hold the whole subtree's.
     */
    val subSeriesCount: Int = 0,
) {
    /**
     * Get the sequence for a specific book.
     */
    fun sequenceFor(bookId: String): Double? = bookSequences[bookId]

    /**
     * Get books sorted by their sequence in this series.
     */
    fun booksSortedBySequence(): List<BookListItem> =
        books.sortedBy { book ->
            // Unnumbered books sort last rather than at zero — they are unplaced, not first.
            bookSequences[book.id.value] ?: Double.MAX_VALUE
        }

    /** The book after [bookId] in sequence order, or null when [bookId] is last or not in this series. */
    fun bookAfter(bookId: String): BookListItem? {
        val books = booksSortedBySequence()
        val index = books.indexOfFirst { it.id.value == bookId }
        return if (index < 0) null else books.getOrNull(index + 1)
    }
}
