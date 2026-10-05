package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.ChildSeriesUi
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesBookSection
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesResumeUi
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesSectionKind
import com.calypsan.listenup.core.BookId

/** The Final Empire, The Well of Ascension, The Hero of Ages — books 1–3 of Mistborn Era 1. */
internal val era1Books: List<BookListItem> =
    listOf(
        seriesBook("fe", "The Final Empire", 1.0, seriesId = "era1"),
        seriesBook("wa", "The Well of Ascension", 2.0, seriesId = "era1"),
        seriesBook("ha", "The Hero of Ages", 3.0, seriesId = "era1"),
    )

internal val elantrisBook: BookListItem = seriesBook("el", "Elantris", 1.0, seriesId = "elantris")

internal val warbreakerBook: BookListItem = seriesBook("wb", "Warbreaker", null, seriesId = "cosmere")

/**
 * Cosmere as its own page shows it: Mistborn (which holds Mistborn Era 1) and Elantris as
 * sub-series, then Warbreaker under "Also in Cosmere". Elantris is finished, so it starts folded.
 */
internal fun groupedCosmere(
    canEditHierarchy: Boolean = false,
    isOnline: Boolean = true,
    elantrisCollapsed: Boolean = true,
): SeriesDetailUiState.Ready =
    readySeries(
        seriesId = "cosmere",
        seriesName = "Cosmere",
        books = era1Books + elantrisBook + warbreakerBook,
        bookProgress = mapOf(BookId("ha") to 0.4f),
        finishedBookIds = setOf(BookId("fe"), BookId("wa"), BookId("el")),
        resumeTarget = BookId("ha"),
    ).copy(
        childSeries =
            listOf(
                ChildSeriesUi("mistborn", "Mistborn", null, bookCount = 3, finishedCount = 2, subSeriesCount = 1),
                ChildSeriesUi("elantris", "Elantris", null, bookCount = 1, finishedCount = 1),
            ),
        bookSections =
            listOf(
                section(
                    "mistborn",
                    "Mistborn",
                    SeriesSectionKind.SUB_SERIES,
                    depth = 1,
                    books = emptyList(),
                    bookCount = 3,
                    finished = 2,
                ),
                section(
                    "era1",
                    "Mistborn Era 1",
                    SeriesSectionKind.SUB_SERIES,
                    depth = 2,
                    books = era1Books,
                    bookCount = 3,
                    finished = 2,
                    path = listOf("Mistborn", "Mistborn Era 1"),
                ),
                section(
                    "elantris",
                    "Elantris",
                    SeriesSectionKind.SUB_SERIES,
                    depth = 1,
                    books = if (elantrisCollapsed) emptyList() else listOf(elantrisBook),
                    bookCount = 1,
                    finished = 1,
                    collapsed = elantrisCollapsed,
                ),
                section(
                    "cosmere",
                    "Cosmere",
                    SeriesSectionKind.OWN_BOOKS,
                    depth = 1,
                    books = listOf(warbreakerBook),
                    bookCount = 1,
                    finished = 0,
                ),
            ),
        resumeBook =
            SeriesResumeUi(
                bookId = "ha",
                title = "The Hero of Ages",
                seriesName = "Mistborn Era 1",
                sequence = "3",
                hasStarted = true,
            ),
        canEditHierarchy = canEditHierarchy,
        isOnline = isOnline,
    )

/** Mistborn Era 1's own page: a flat child, two levels down. */
internal fun childEra1(): SeriesDetailUiState.Ready =
    readySeries(
        seriesId = "era1",
        seriesName = "Mistborn Era 1",
        books = era1Books,
        bookProgress = mapOf(BookId("ha") to 0.4f),
        resumeTarget = BookId("ha"),
    ).copy(
        ancestors = listOf(SeriesCrumb("cosmere", "Cosmere"), SeriesCrumb("mistborn", "Mistborn")),
        bookSections =
            listOf(
                section(
                    "era1",
                    "Mistborn Era 1",
                    SeriesSectionKind.OWN_BOOKS,
                    depth = 1,
                    books = era1Books,
                    bookCount = 3,
                    finished = 0,
                ),
            ),
        resumeBook =
            SeriesResumeUi(
                bookId = "ha",
                title = "The Hero of Ages",
                seriesName = "Mistborn Era 1",
                sequence = "3",
                hasStarted = true,
            ),
    )

/** Cosmere before the reader has begun any of it: the button starts its first book. */
internal fun unstartedCosmere(): SeriesDetailUiState.Ready =
    groupedCosmere().copy(
        bookProgress = emptyMap(),
        finishedBookIds = emptySet(),
        resumeTarget = BookId("fe"),
        resumeBook =
            SeriesResumeUi(
                bookId = "fe",
                title = "The Final Empire",
                seriesName = "Mistborn Era 1",
                sequence = "1",
                hasStarted = false,
            ),
    )

@Suppress("LongParameterList")
private fun section(
    seriesId: String,
    title: String,
    kind: SeriesSectionKind,
    depth: Int,
    books: List<BookListItem>,
    bookCount: Int,
    finished: Int,
    collapsed: Boolean = false,
    path: List<String> = listOf(title),
) = SeriesBookSection(
    key = (if (kind == SeriesSectionKind.SUB_SERIES) "sub:" else "own:") + seriesId,
    seriesId = seriesId,
    title = title,
    kind = kind,
    path = path,
    depth = depth,
    books = books,
    bookCount = bookCount,
    finishedCount = finished,
    isCollapsed = collapsed,
)
