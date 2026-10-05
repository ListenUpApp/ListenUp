package com.calypsan.listenup.client.features.seriesdetail

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.seriesdetail.ChildSeriesUi
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesBookSection
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesResumeUi
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesSectionKind
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Duration.Companion.hours

/** The Cosmere of the design canvas, cut down to what the series page tests read. */
internal object Cosmere {
    fun book(
        id: String,
        title: String,
        seriesId: String,
        sequence: Double?,
    ) = BookListItem(
        id = BookId(id),
        title = title,
        series = listOf(BookSeries(seriesId = seriesId, seriesName = seriesId, sequence = sequence)),
        // A local path: a null one sends the cover to Koin for a server fallback, absent in these tests.
        coverPath = "/tmp/cover-$id.webp",
        authors = emptyList(),
        narrators = emptyList(),
        duration = 1.hours.inWholeMilliseconds,
        libraryId = LibraryId("lib1"),
        folderId = FolderId("folder1"),
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

    val finalEmpire = book("fe", "The Final Empire", "era1", 1.0)
    val heroOfAges = book("hoa", "The Hero of Ages", "era1", 3.0)
    val elantris = book("el", "Elantris", "elantris", 1.0)
    val warbreaker = book("wb", "Warbreaker", "cosmere", null)

    val mistborn =
        ChildSeriesUi(
            id = "mistborn",
            name = "Mistborn",
            coverPath = null,
            bookCount = 8,
            finishedCount = 2,
            subSeriesCount = 2,
        )
    val elantrisSeries =
        ChildSeriesUi(id = "elantris", name = "Elantris", coverPath = null, bookCount = 1, finishedCount = 1)

    private fun section(
        id: String,
        title: String,
        kind: SeriesSectionKind,
        depth: Int,
        books: List<BookListItem>,
        bookCount: Int = books.size,
        collapsed: Boolean = false,
    ) = SeriesBookSection(
        key = "${if (kind == SeriesSectionKind.SUB_SERIES) "sub" else "own"}:$id",
        seriesId = id,
        title = title,
        kind = kind,
        path = listOf(title),
        depth = depth,
        books = books,
        bookCount = bookCount,
        finishedCount = 0,
        isCollapsed = collapsed,
    )

    /** Cosmere's own page: two sub-series, a nested group, a folded finished group, and its own book. */
    fun parentPage(
        canEdit: Boolean = false,
        online: Boolean = true,
    ) = SeriesDetailUiState.Ready(
        seriesId = "cosmere",
        seriesName = "Cosmere",
        seriesDescription = null,
        seriesAuthors = emptyList(),
        seriesNarrator = null,
        coverPath = null,
        featuredBookId = null,
        totalDuration = 4.hours,
        books = listOf(finalEmpire, heroOfAges, elantris, warbreaker),
        bookProgress = mapOf(heroOfAges.id to 0.42f),
        finishedBookIds = setOf(finalEmpire.id, elantris.id),
        resumeTarget = heroOfAges.id,
        childSeries = listOf(mistborn, elantrisSeries),
        bookSections =
            listOf(
                section("mistborn", "Mistborn", SeriesSectionKind.SUB_SERIES, 1, emptyList(), bookCount = 8),
                section("era1", "Mistborn Era 1", SeriesSectionKind.SUB_SERIES, 2, listOf(finalEmpire, heroOfAges)),
                section(
                    "elantris",
                    "Elantris",
                    SeriesSectionKind.SUB_SERIES,
                    1,
                    emptyList(),
                    bookCount = 4,
                    collapsed = true,
                ),
                section("cosmere", "Cosmere", SeriesSectionKind.OWN_BOOKS, 1, listOf(warbreaker)),
            ),
        resumeBook =
            SeriesResumeUi(
                bookId = "hoa",
                title = "The Hero of Ages",
                seriesName = "Mistborn Era 1",
                sequence = "3",
            ),
        canEditHierarchy = canEdit,
        isOnline = online,
    )

    /** Mistborn Era 1's page: a flat child, under Cosmere › Mistborn. */
    val childPage =
        SeriesDetailUiState.Ready(
            seriesId = "era1",
            seriesName = "Mistborn Era 1",
            seriesDescription = null,
            seriesAuthors = emptyList(),
            seriesNarrator = null,
            coverPath = null,
            featuredBookId = null,
            totalDuration = 2.hours,
            books = listOf(finalEmpire, heroOfAges),
            bookProgress = emptyMap(),
            finishedBookIds = emptySet(),
            resumeTarget = finalEmpire.id,
            ancestors = listOf(SeriesCrumb("cosmere", "Cosmere"), SeriesCrumb("mistborn", "Mistborn")),
            bookSections =
                listOf(
                    section("era1", "Mistborn Era 1", SeriesSectionKind.OWN_BOOKS, 1, listOf(finalEmpire, heroOfAges)),
                ),
        )
}
