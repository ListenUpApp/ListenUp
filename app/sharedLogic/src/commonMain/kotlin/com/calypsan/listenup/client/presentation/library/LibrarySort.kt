package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.util.sortableTitle

/**
 * Precomputed sort keys for the three-level SERIES sort on [BookListItem].
 *
 * Extracted as a file-level private class (rather than a local data class inside a `when` branch)
 * to avoid the duplicate JVM class-name error that arises when two `when` branches in the same
 * function each declare a local `data class` with the same name.
 */
private data class BookSeriesSortKey(
    val book: BookListItem,
    val seriesName: String,
    val sequence: Double,
    val title: String,
)

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
internal fun sortBooks(
    books: List<BookListItem>,
    state: SortState,
    ignoreArticles: Boolean,
    listenerAverages: Map<String, ListenerAverage>,
    combinedScores: Map<String, CombinedScore>,
): List<BookListItem> {
    val isAsc = state.direction == SortDirection.ASCENDING

    // Schwartzian transform: compute each sort key ONCE per element, then sort by the
    // precomputed value. Without this, comparators like `.sortedBy { it.title.sortableTitle(...) }`
    // invoke the key function O(n*log n) times per sort — with regex + toLowerCase that's
    // significant allocation churn on a 1000+ book library every time the flow emits.
    return when (state.category) {
        SortCategory.TITLE -> {
            // Key: sortable title string (regex strip + lowercase) — computed once per element.
            val keyed = books.map { it to it.title.sortableTitle(ignoreArticles) }
            val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
            sorted.map { it.first }
        }

        SortCategory.AUTHOR -> {
            // Primary key: authorNames (joinToString + lowercase) — computed once per element.
            // Secondary key: title lowercase (tie-breaker), also precomputed.
            val keyed = books.map { Triple(it, it.authorNames.lowercase(), it.title.lowercase()) }
            val sorted =
                if (isAsc) {
                    keyed.sortedWith(compareBy({ it.second }, { it.third }))
                } else {
                    keyed.sortedWith(
                        compareByDescending<Triple<BookListItem, String, String>> { it.second }.thenBy { it.third },
                    )
                }
            sorted.map { it.first }
        }

        SortCategory.DURATION -> {
            if (isAsc) books.sortedBy { it.duration } else books.sortedByDescending { it.duration }
        }

        SortCategory.YEAR -> {
            // Primary key: publish year (null sentinel differs by direction) + secondary: title lowercase.
            val nullYear = if (isAsc) Int.MAX_VALUE else 0
            val keyed = books.map { Triple(it, it.publishYear ?: nullYear, it.title.lowercase()) }
            val sorted =
                if (isAsc) {
                    keyed.sortedWith(compareBy({ it.second }, { it.third }))
                } else {
                    keyed.sortedWith(
                        compareByDescending<Triple<BookListItem, Int, String>> { it.second }.thenBy { it.third },
                    )
                }
            sorted.map { it.first }
        }

        SortCategory.ADDED -> {
            if (isAsc) {
                books.sortedBy {
                    it.addedAt.epochMillis
                }
            } else {
                books.sortedByDescending { it.addedAt.epochMillis }
            }
        }

        SortCategory.RATING -> {
            sortByRatedAverage(books, isAsc) { combinedScores[it.id.value]?.average }
        }

        SortCategory.LISTENER_RATING -> {
            sortByRatedAverage(books, isAsc) { listenerAverages[it.id.value]?.averageHalfStars }
        }

        SortCategory.SERIES -> {
            // Three-level key: series name (lowercase, null sentinel), sequence (Float), title.
            // All three keys are precomputed once per element via BookSeriesSortKey (file-level
            // private class — can't use a local data class here without a JVM name collision
            // across the when-branches).
            val nullSeriesName = if (isAsc) "￿" else ""
            val nullSequence = if (isAsc) Double.MAX_VALUE else 0.0
            val keyed =
                books.map { book ->
                    BookSeriesSortKey(
                        book = book,
                        seriesName = book.seriesName?.lowercase() ?: nullSeriesName,
                        sequence = book.seriesSequence ?: nullSequence,
                        title = book.title.lowercase(),
                    )
                }
            val sorted =
                if (isAsc) {
                    keyed.sortedWith(compareBy({ it.seriesName }, { it.sequence }, { it.title }))
                } else {
                    keyed.sortedWith(
                        compareByDescending<BookSeriesSortKey> { it.seriesName }
                            .thenByDescending { it.sequence }
                            .thenBy { it.title },
                    )
                }
            sorted.map { it.book }
        }

        // Not applicable for books
        SortCategory.NAME, SortCategory.BOOK_COUNT -> {
            books
        }
    }
}

/**
 * Shared by [SortCategory.RATING] and [SortCategory.LISTENER_RATING]: unrated books last in
 * BOTH directions (an absent average is not a zero), equal averages falling back to title
 * (lowercase, ascending) — like AUTHOR and YEAR. [averageFor] is the one thing that differs
 * between the two categories.
 */
private fun sortByRatedAverage(
    books: List<BookListItem>,
    isAsc: Boolean,
    averageFor: (BookListItem) -> Double?,
): List<BookListItem> {
    val keyed =
        books.mapNotNull { book ->
            averageFor(book)?.let { average -> Triple(book, average, book.title.lowercase()) }
        }
    val unrated = books.filter { averageFor(it) == null }
    val sorted =
        if (isAsc) {
            keyed.sortedWith(compareBy({ it.second }, { it.third }))
        } else {
            keyed.sortedWith(
                compareByDescending<Triple<BookListItem, Double, String>> { it.second }.thenBy { it.third },
            )
        }
    return sorted.map { it.first } + unrated
}

internal fun sortSeries(
    series: List<SeriesWithBooks>,
    state: SortState,
    ignoreArticles: Boolean,
): List<SeriesWithBooks> {
    val isAsc = state.direction == SortDirection.ASCENDING

    return when (state.category) {
        SortCategory.NAME -> {
            // Key: article-aware sortable name (so "The Wandering Inn" files under W) — computed once.
            val keyed = series.map { it to it.series.name.sortableTitle(ignoreArticles) }
            val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
            sorted.map { it.first }
        }

        SortCategory.BOOK_COUNT -> {
            if (isAsc) series.sortedBy { it.books.size } else series.sortedByDescending { it.books.size }
        }

        SortCategory.ADDED -> {
            if (isAsc) {
                series.sortedBy { it.series.createdAt.epochMillis }
            } else {
                series.sortedByDescending { it.series.createdAt.epochMillis }
            }
        }

        // Default to name sort for unsupported categories
        SortCategory.TITLE,
        SortCategory.AUTHOR,
        SortCategory.DURATION,
        SortCategory.YEAR,
        SortCategory.RATING,
        SortCategory.LISTENER_RATING,
        SortCategory.SERIES,
        -> {
            val keyed = series.map { it to it.series.name.sortableTitle(ignoreArticles) }
            keyed.sortedBy { it.second }.map { it.first }
        }
    }
}

internal fun sortContributors(
    contributors: List<ContributorWithBookCount>,
    state: SortState,
): List<ContributorWithBookCount> {
    val isAsc = state.direction == SortDirection.ASCENDING

    return when (state.category) {
        SortCategory.NAME -> {
            // Key: name lowercase — computed once per element.
            val keyed = contributors.map { it to it.contributor.name.lowercase() }
            val sorted = if (isAsc) keyed.sortedBy { it.second } else keyed.sortedByDescending { it.second }
            sorted.map { it.first }
        }

        SortCategory.BOOK_COUNT -> {
            if (isAsc) contributors.sortedBy { it.bookCount } else contributors.sortedByDescending { it.bookCount }
        }

        // Default to name sort for unsupported categories
        SortCategory.TITLE,
        SortCategory.AUTHOR,
        SortCategory.DURATION,
        SortCategory.YEAR,
        SortCategory.ADDED,
        SortCategory.RATING,
        SortCategory.LISTENER_RATING,
        SortCategory.SERIES,
        -> {
            val keyed = contributors.map { it to it.contributor.name.lowercase() }
            keyed.sortedBy { it.second }.map { it.first }
        }
    }
}
