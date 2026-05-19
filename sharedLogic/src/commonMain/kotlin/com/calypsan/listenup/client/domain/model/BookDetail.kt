package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp

/**
 * Domain model for a book on its detail screen.
 *
 * Carries the full set of fields including [allContributors], [genres], and
 * [tags] — the three fields that always live empty on list-surface read paths.
 * Convert to [BookListItem] via [toListItem] when handing off to a list consumer.
 *
 * Deliberately kept separate from [BookListItem] (see [BookSummaryFields] for the rationale).
 * Computed properties and [formatDuration] are supplied once via [BookSummaryFields].
 */
data class BookDetail(
    val id: BookId,
    val title: String,
    val sortTitle: String? = null,
    val subtitle: String? = null,
    override val authors: List<BookContributor>,
    override val narrators: List<BookContributor>,
    override val duration: Long,
    override val coverPath: String?,
    val coverBlurHash: String? = null,
    val dominantColor: Int? = null,
    val darkMutedColor: Int? = null,
    val vibrantColor: Int? = null,
    val addedAt: Timestamp,
    val updatedAt: Timestamp,
    val description: String? = null,
    override val series: List<BookSeries> = emptyList(),
    val publishYear: Int? = null,
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean = false,
    val rating: Double? = null,
    val allContributors: List<BookContributor> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val hasScanWarning: Boolean = false,
) : BookSummaryFields {
    fun toListItem(): BookListItem =
        BookListItem(
            id = id,
            title = title,
            sortTitle = sortTitle,
            subtitle = subtitle,
            authors = authors,
            narrators = narrators,
            duration = duration,
            coverPath = coverPath,
            coverBlurHash = coverBlurHash,
            dominantColor = dominantColor,
            darkMutedColor = darkMutedColor,
            vibrantColor = vibrantColor,
            addedAt = addedAt,
            updatedAt = updatedAt,
            description = description,
            series = series,
            publishYear = publishYear,
            publisher = publisher,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            rating = rating,
        )
}
