package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp

/**
 * Domain model for a book in list/shelf surfaces.
 *
 * No `genres`, `tags`, or `allContributors` — those are detail-only and would
 * always be empty here. Use [BookDetail] when those fields are required.
 *
 * Deliberately kept separate from [BookDetail] (see [BookSummaryFields] for the rationale).
 * Computed properties and [formatDuration] are supplied once via [BookSummaryFields].
 */
data class BookListItem(
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
    val hasDocuments: Boolean = false,
) : BookSummaryFields {
    /** The book id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value
}
