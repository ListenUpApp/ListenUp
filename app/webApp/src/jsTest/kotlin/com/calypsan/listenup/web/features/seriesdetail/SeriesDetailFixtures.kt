package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A loaded series, for specs about the hero, the reading order and the resume target.
 *
 * Lives here rather than in the page it drives — the same split
 * [readyContributor][com.calypsan.listenup.web.features.contributordetail.readyContributor] makes —
 * so the page renders whatever the shared ViewModel gives it.
 *
 * [totalDuration] defaults to a value the [books] do NOT sum to. The real ViewModel derives the
 * stat from every book's duration, and a fixture where the two happen to agree would hide a
 * regression in which the hero quietly summed the rows it was rendering instead of reading the
 * stat it was handed.
 */
internal fun readySeries(
    seriesId: String = "s-stormlight",
    seriesName: String = "The Stormlight Archive",
    seriesDescription: String? = null,
    seriesAuthors: List<BookContributor> = listOf(BookContributor(id = "c1", name = "Brandon Sanderson")),
    books: List<BookListItem> =
        listOf(seriesBook("b1", "The Way of Kings", 1.0), seriesBook("b2", "Words of Radiance", 2.0)),
    bookProgress: Map<BookId, Float> = emptyMap(),
    finishedBookIds: Set<BookId> = emptySet(),
    resumeTarget: BookId? = books.firstOrNull()?.id,
    totalDuration: Duration = DEFAULT_TOTAL_DURATION,
): SeriesDetailUiState.Ready =
    SeriesDetailUiState.Ready(
        seriesId = seriesId,
        seriesName = seriesName,
        seriesDescription = seriesDescription,
        seriesAuthors = seriesAuthors,
        seriesNarrator = "Michael Kramer",
        coverPath = null,
        featuredBookId = books.firstOrNull()?.id?.value,
        totalDuration = totalDuration,
        books = books,
        bookProgress = bookProgress,
        finishedBookIds = finishedBookIds,
        resumeTarget = resumeTarget,
    )

/**
 * A book in [seriesId] at [sequence].
 *
 * The membership is on the book, not alongside it, because that is where the page reads a position
 * from — a fixture that carried the sequence separately would let a page that renders the wrong
 * series' number still pass.
 */
internal fun seriesBook(
    id: String,
    title: String,
    sequence: Double?,
    seriesId: String = "s-stormlight",
    authors: List<BookContributor> = listOf(BookContributor(id = "c1", name = "Brandon Sanderson")),
    extraSeries: List<BookSeries> = emptyList(),
): BookListItem =
    BookListItem(
        id = BookId(id),
        libraryId = LibraryId("library-1"),
        folderId = FolderId("folder-1"),
        title = title,
        authors = authors,
        narrators = emptyList(),
        duration = DEFAULT_BOOK_DURATION_MS,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        // [extraSeries] comes FIRST on purpose. A book in two series has two positions, and a
        // page that read `series.first()` would be right by luck if this series led the list —
        // so it does not. Nothing guarantees an order here in production either.
        series =
            extraSeries + BookSeries(seriesId = seriesId, seriesName = "The Stormlight Archive", sequence = sequence),
    )

private val DEFAULT_TOTAL_DURATION = 92.hours

private const val DEFAULT_BOOK_DURATION_MS = 45L * 3600 * 1000
