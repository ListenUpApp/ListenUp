package com.calypsan.listenup.web.features.serieslist

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.LibraryFacet
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Categories the Series tab sorts by — the three iOS's `SeriesContent` offers.
 *
 * An explicit list rather than `SortCategory.entries` for the reason the Books tab keeps one: the
 * enum also carries categories that only mean something on another tab, and Title in particular is
 * a book's sort, not a series'.
 */
private val SERIES_SORT_CATEGORIES = listOf(SortCategory.NAME, SortCategory.BOOK_COUNT, SortCategory.ADDED)

/**
 * The Library's Series tab — every series in the library, in the reader's chosen order.
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
        H3 { Text("Library") }
        if (state is LibraryUiState.Loaded) SeriesSortControl(state, onEvent)
    }
    FacetRow(active = LibraryFacet.Series, onSelect = onSelectFacet)

    when (state) {
        is LibraryUiState.Loading -> {
            Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
        }

        is LibraryUiState.Error -> {
            Div(attrs = { classes("empty") }) { P { Text(state.message) } }
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
        Div(attrs = { classes("empty") }) {
            H3 { Text(if (state.isBuildingInitialLibrary) "Still reading your library" else "No series yet") }
            P {
                Text(
                    if (state.isBuildingInitialLibrary) {
                        "Series appear as the scan works through your books."
                    } else {
                        "Books grouped into a series will show up here."
                    },
                )
            }
        }
        return
    }

    Div(attrs = { classes("srs-grid") }) {
        state.series.forEach { entry ->
            SeriesCard(
                entry = entry,
                progress = state.seriesProgress[entry.series.id],
                onOpen = { onOpenSeries(entry.series.id.value) },
            )
        }
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
    progress: SeriesProgress?,
    onOpen: () -> Unit,
) {
    Button(attrs = {
        classes("srs-card")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        // The first book stands for the series, the way a shelf shows its first spine. Absent when
        // the series somehow holds no books — a blank frame beats a broken image request.
        entry.books.firstOrNull()?.let { first ->
            Div(attrs = { classes("srs-cover") }) {
                Cover(title = first.title, imageUrl = coverUrl(first.id.value, first.coverHash, CARD_COVER_RUNG))
            }
        }
        Div(attrs = { classes("srs-meta") }) {
            Span(attrs = { classes("srs-name") }) { Text(entry.series.name) }
            Span(attrs = { classes("srs-count") }) { Text(bookCountLabel(entry.books.size)) }
            progress?.let { ProgressLine(it) }
        }
    }
}

/**
 * "3 of 7 finished", or the completed state.
 *
 * Absent entirely when the aggregate says nothing has been started: a "0 of 7" line on every
 * untouched series is noise on a page whose job is to show what there is to read.
 */
@Composable
private fun ProgressLine(progress: SeriesProgress) {
    if (progress.isNotStarted) return
    Span(attrs = { classes("srs-progress") }) {
        Text(
            if (progress.isComplete) {
                "Finished"
            } else {
                "${progress.finishedCount} of ${progress.totalCount} finished"
            },
        )
    }
}

@Composable
private fun SeriesSortControl(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
) {
    Div(attrs = { classes("lib-sort") }) {
        SERIES_SORT_CATEGORIES.forEach { category ->
            Div(attrs = {
                classes("lib-sort-option")
                if (state.seriesSortState.category == category) classes("is-active")
                onClick { onEvent(LibraryUiEvent.SeriesCategoryChanged(category)) }
            }) { Text(category.label) }
        }
        Div(attrs = {
            classes("lib-sort-direction")
            onClick { onEvent(LibraryUiEvent.SeriesDirectionToggled) }
        }) { Text(if (state.seriesSortState.direction == SortDirection.ASCENDING) "↑" else "↓") }
    }
}

private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

/** Series cards are small; the smallest server rung covers this cell comfortably. */
private const val CARD_COVER_RUNG = 200
