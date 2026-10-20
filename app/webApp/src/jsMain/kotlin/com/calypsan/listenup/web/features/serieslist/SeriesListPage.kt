package com.calypsan.listenup.web.features.serieslist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.SortDropdown
import com.calypsan.listenup.web.design.VirtualList
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.features.library.routeFor
import com.calypsan.listenup.web.features.library.WithJumpRail
import com.calypsan.listenup.web.features.library.groupedCount
import com.calypsan.listenup.web.features.seriesdetail.seriesAndBookCount
import com.calypsan.listenup.web.features.seriesdetail.seriesBookCount
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import com.calypsan.listenup.web.motion.CoverSurface
import com.calypsan.listenup.web.motion.heroTile
import com.calypsan.listenup.web.motion.recordHeroOrigin
import com.calypsan.listenup.web.motion.seriesHeroKey
import org.w3c.dom.Element

/**
 * Categories the Series tab sorts by — the three iOS's `SeriesContent` offers.
 *
 * An explicit list rather than `SortCategory.entries` for the reason the Books tab keeps one: the
 * enum also carries categories that only mean something on another tab, and Title in particular is
 * a book's sort, not a series'.
 */
private val SERIES_SORT_CATEGORIES = listOf(SortCategory.NAME, SortCategory.BOOK_COUNT, SortCategory.ADDED)

/**
 * The Library's Series tab — every top-level series in the library, in the reader's chosen order. A
 * parent series' card stands for its whole subtree.
 *
 * ⛔ Driven by the shared [com.calypsan.listenup.client.presentation.library.LibraryViewModel]
 * through the existing library session, NOT by a repository observe of its own. That distinction is
 * the whole point: `LibraryViewModel` already sorts `series` by `seriesSortState` and already
 * aggregates `seriesProgress`, and `SeriesCategoryChanged`/`SeriesDirectionToggled` already persist
 * the choice so it follows the reader to their phone. Riding the repository directly — the shape
 * the Contributors list took — is exactly why that list still has no sort at all.
 *
 * Pure in [state]: the session wiring lives one level up.
 */
@Composable
fun SeriesListPage(
    state: LibraryUiState,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenSeries: (String) -> Unit,
    onSelectFacet: (LibraryFacet) -> Unit,
) {
    // Header and facets render in every state, for the reason the Books tab gives: they are
    // navigation rather than data, and hiding them during a long first sync strands the reader.
    Div(attrs = { classes("lib-header") }) {
        PageHeader(title = "Series") {
            if (state is LibraryUiState.Loaded) SeriesSortControl(state, onEvent)
        }
    }
    FacetRow(active = LibraryFacet.Series, hrefFor = { routeFor(it).toUrl() }, onSelect = onSelectFacet)

    when (state) {
        is LibraryUiState.Loading -> {
            LoadingState()
        }

        is LibraryUiState.Error -> {
            EmptyState(title = "Your library can't be shown", body = state.message)
        }

        is LibraryUiState.Loaded -> {
            LoadedSeries(state, onOpenSeries)
        }
    }
}

@Composable
private fun LoadedSeries(
    state: LibraryUiState.Loaded,
    onOpenSeries: (String) -> Unit,
) {
    if (state.series.isEmpty()) {
        EmptyState(
            title = if (state.isBuildingInitialLibrary) "Still reading your library" else "No series yet",
            body =
                if (state.isBuildingInitialLibrary) {
                    "Series appear as the scan works through your books."
                } else {
                    "Books grouped into a series will show up here."
                },
        )
        return
    }

    // Windowed, like the library grid: a big library has hundreds of series. Every card is the same
    // height — the name is clamped to two lines and the meta block holds its size whether or not
    // there is progress to show — which is what lets rows be counted rather than measured.
    // Letter headings from the shared sections (a name sort only, article-aware so "The Wandering
    // Inn" files under W) — the same letters Android's and iOS's series indexes use.
    val letterById =
        remember(state.series, state.seriesLetters) {
            buildMap {
                state.seriesLetters.forEach { section ->
                    for (
                    index in section.firstIndex until
                        minOf(
                            section.firstIndex + section.count,
                            state.series.size,
                        )
                    ) {
                        put(
                            state.series[index]
                                .series.id.value,
                            section.letter.first(),
                        )
                    }
                }
            }
        }
    // A letter that recurs is captioned with its first run's size, as the Books grid's are.
    val captionByLetter =
        remember(state.seriesLetters) {
            state.seriesLetters.groupBy { it.letter.first() }.mapValues { (_, runs) -> runs.first().count }
        }
    // The same A–Z jump rail as Books (spec L25/L26), over the same shared letters.
    WithJumpRail(state.seriesLetters) { jump ->
        VirtualList(
            items = state.series,
            key = { it.series.id.value },
            containerClass = "srs-grid",
            itemSelector = ".srs-card",
            label = "Series",
            sectionOf = { letterById[it.series.id.value] },
            headerSelector = ".lib-section",
            header = { letter ->
                Div(attrs = { classes("lib-section") }) {
                    Span { Text(letter.toString()) }
                    Span(attrs = { classes("lib-section-c") }) {
                        Text(captionByLetter[letter]?.let { "${groupedCount(it)} series" }.orEmpty())
                    }
                }
            },
            jump = jump,
        ) { entry ->
            SeriesCard(
                entry = entry,
                // iOS's `progressFor` fallback: no aggregate for a series means nothing finished yet.
                progress =
                    state.seriesProgress[entry.series.id]
                        ?: SeriesProgress(finishedCount = 0, totalCount = entry.books.size),
                onOpen = { onOpenSeries(entry.series.id.value) },
            )
        }
    }
}

/**
 * One series (spec c6, board `Library-WebPhone`): up to three of its covers overlapping, its book count in ink on
 * them, its name, "7 books · Matt Dinniman", and how far through it the reader is, as a bar and in words.
 *
 * A `<button>` rather than a div with a click handler — it goes somewhere, so it has to be keyboard-reachable and
 * announce itself. Its name is its text: the covers and the badge are decoration, the count is in the meta line.
 */
@Composable
private fun SeriesCard(
    entry: SeriesWithBooks,
    progress: SeriesProgress,
    onOpen: () -> Unit,
) {
    Button(attrs = {
        classes("srs-card")
        attr("type", "button")
        onClick { event ->
            // Where the covers are NOW, before its page goes away — the series hero flies in from here.
            (event.currentTarget as? Element)?.querySelector(".srs-cover")?.let {
                recordHeroOrigin(seriesHeroKey(entry.series.id.value), CoverSurface.GRID, it)
            }
            onOpen()
        }
    }) {
        // Absent when the series somehow holds no books — a blank frame beats a broken image request.
        if (entry.books.isNotEmpty()) {
            // A parent series wears a stacked edge: the card stands for more than one series.
            Div(attrs = {
                classes("srs-cover", "srs-stack")
                if (entry.subSeriesCount > 0) classes("is-stack")
                attr("aria-hidden", "true")
                // The return leg: Back from the series flies its hero home into these covers.
                heroTile(seriesHeroKey(entry.series.id.value))
            }) {
                entry.books.take(STACK_COVERS).forEachIndexed { index, book ->
                    Cover(
                        title = book.title,
                        imageUrl = coverUrl(book.id.value, book.coverHash, CARD_COVER_RUNG),
                        size = STACK_COVER_SIZE,
                        radius = STACK_COVER_RADIUS,
                        decorative = true,
                        attrs = { classes("srs-c", "srs-c-$index") },
                    )
                }
                if (entry.books.size > 1) {
                    Span(attrs = { classes("srs-badge") }) { Text(entry.books.size.toString()) }
                }
            }
        }
        Div(attrs = { classes("srs-meta") }) {
            Span(attrs = { classes("srs-name") }) { Text(entry.series.name) }
            Span(attrs = { classes("srs-count") }) { Text(metaLine(entry)) }
            ProgressBar(value = progress.fraction, label = "Series progress", attrs = { classes("srs-bar") })
            ProgressLine(progress)
        }
    }
}

/**
 * "Complete", "3 of 7", or a muted "Not started" — worded exactly as iOS and Android word it
 * (`series.complete`, `series.x_of_y`, `series.not_started`).
 */
@Composable
private fun ProgressLine(progress: SeriesProgress) {
    Span(attrs = {
        classes("srs-progress")
        when {
            progress.isComplete -> classes("is-complete")
            progress.isNotStarted -> classes("srs-progress-idle")
        }
    }) {
        Text(
            when {
                progress.isComplete -> "Complete"
                progress.isNotStarted -> "Not started"
                else -> "${progress.finishedCount} of ${progress.totalCount}"
            },
        )
    }
}

@Composable
private fun SeriesSortControl(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
) {
    SortDropdown(
        noun = "series",
        categories = SERIES_SORT_CATEGORIES,
        state = state.seriesSortState,
        onCategory = { onEvent(LibraryUiEvent.SeriesCategoryChanged(it)) },
        onToggleDirection = { onEvent(LibraryUiEvent.SeriesDirectionToggled) },
        // Series by name sort article-aware ("The Wandering Inn" under W), so the toggle belongs here too.
        ignoreArticles = state.ignoreTitleArticles.takeIf { state.seriesSortState.category == SortCategory.NAME },
        onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
    )
}

/**
 * "6 books · Matt Dinniman", or for a parent series "4 series · 23 books" — the Library shows top-level series only,
 * so a parent's card is the one place its sub-series are counted from here. The author is the first book's first.
 */
private fun metaLine(entry: SeriesWithBooks): String {
    val count =
        if (entry.subSeriesCount > 0) {
            seriesAndBookCount(entry.subSeriesCount, entry.books.size)
        } else {
            seriesBookCount(entry.books.size)
        }
    val author =
        entry.books
            .firstOrNull()
            ?.authors
            ?.firstOrNull()
            ?.name
    return if (author != null) "$count · $author" else count
}

/** Series cards are small; the smallest server rung covers this cell comfortably. */
private const val CARD_COVER_RUNG = 200

/** Three covers at most (spec c6, open question 10), 72px each, 20px apart (board `Library-WebPhone`). */
private const val STACK_COVERS = 3
private const val STACK_COVER_SIZE = 72
private const val STACK_COVER_RADIUS = 6

