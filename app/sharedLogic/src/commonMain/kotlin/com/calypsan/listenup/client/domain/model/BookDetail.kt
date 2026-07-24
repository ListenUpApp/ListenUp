package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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
    /** Library that owns this book. */
    val libraryId: LibraryId,
    /** Library folder that contains this book's audio files. */
    val folderId: FolderId,
    val title: String,
    val sortTitle: String? = null,
    val subtitle: String? = null,
    override val authors: List<BookContributor>,
    override val narrators: List<BookContributor>,
    override val duration: Long,
    override val coverPath: String?,
    override val coverHash: String? = null,
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
    val moods: List<Mood> = emptyList(),
    /** Audio files for this book; hydrated only for the detail view. Used for the format summary. */
    val audioFiles: List<AudioFile> = emptyList(),
) : BookSummaryFields {
    /** The book id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

    fun toListItem(): BookListItem =
        BookListItem(
            id = id,
            libraryId = libraryId,
            folderId = folderId,
            title = title,
            sortTitle = sortTitle,
            subtitle = subtitle,
            authors = authors,
            narrators = narrators,
            duration = duration,
            coverPath = coverPath,
            coverHash = coverHash,
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

/**
 * Returns the most-specific genre — the one deepest in the hierarchy, measured by
 * the number of `/` separators in its materialized [Genre.path] (e.g. "Progression
 * Fantasy" at "/fiction/fantasy/progression" beats "Fantasy" at "/fiction/fantasy").
 *
 * On a depth tie the first such genre in list order wins; returns null when the book
 * has no genres. This is the genre surfaced as the hero chip beside the Unabridged flag.
 */
fun BookDetail.mostSpecificGenre(): Genre? = genres.maxByOrNull { it.path.count { c -> c == '/' } }
