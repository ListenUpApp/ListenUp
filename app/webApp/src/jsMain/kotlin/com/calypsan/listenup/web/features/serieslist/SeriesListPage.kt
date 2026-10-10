package com.calypsan.listenup.web.features.serieslist

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.SortControl
import com.calypsan.listenup.web.design.VirtualList
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.LibraryFacet
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
    FacetRow(active = LibraryFacet.Series, onSelect = onSelectFacet)

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
    VirtualList(
        items = state.series,
        key = { it.series.id.value },
        containerClass = "srs-grid",
        itemSelector = ".srs-card",
        label = "Series",
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

/**
 * One series: its first book's cover, its name, and how far through it the reader is.
 *
 * A `<button>` rather than a div with a click handler — it goes somewhere, so it has to be
 * keyboard-reachable and announce itself, the same contract the rest of web's cards carry.
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
            // Where the cover is NOW, before its page goes away — the series hero flies in from here.
            (event.currentTarget as? Element)?.querySelector(".srs-cover")?.let {
                recordHeroOrigin(seriesHeroKey(entry.series.id.value), CoverSurface.GRID, it)
            }
            onOpen()
        }
    }) {
        // The first book stands for the series, the way a shelf shows its first spine. Absent when
        // the series somehow holds no books — a blank frame beats a broken image request.
        entry.books.firstOrNull()?.let { first ->
            // A parent series wears a stacked edge: the card stands for more than one series.
            Div(attrs = {
                classes("srs-cover")
                if (entry.subSeriesCount > 0) classes("is-stack")
                // The return leg: Back from the series flies its hero home into this cover.
                heroTile(seriesHeroKey(entry.series.id.value))
            }) {
                Cover(title = first.title, imageUrl = coverUrl(first.id.value, first.coverHash, CARD_COVER_RUNG))
            }
        }
        Div(attrs = { classes("srs-meta") }) {
            Span(attrs = { classes("srs-name") }) { Text(entry.series.name) }
            Span(attrs = { classes("srs-count") }) { Text(countLine(entry)) }
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
        if (progress.isNotStarted && !progress.isComplete) classes("srs-progress-idle")
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
    SortControl(
        options = SERIES_SORT_CATEGORIES,
        active = state.seriesSortState.category,
        labelOf = { it.label },
        ascending = state.seriesSortState.direction == SortDirection.ASCENDING,
        onSelect = { onEvent(LibraryUiEvent.SeriesCategoryChanged(it)) },
        onToggleDirection = { onEvent(LibraryUiEvent.SeriesDirectionToggled) },
    )
}

/**
 * "6 books", or for a parent series "4 series · 23 books" — the Library shows top-level series only,
 * so a parent's card is the one place its sub-series are counted from here.
 */
private fun countLine(entry: SeriesWithBooks): String =
    if (entry.subSeriesCount > 0) {
        seriesAndBookCount(entry.subSeriesCount, entry.books.size)
    } else {
        seriesBookCount(entry.books.size)
    }

/** Series cards are small; the smallest server rung covers this cell comfortably. */
private const val CARD_COVER_RUNG = 200
