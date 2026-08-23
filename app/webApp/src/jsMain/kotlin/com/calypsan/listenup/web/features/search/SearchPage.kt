package com.calypsan.listenup.web.features.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Pill
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Search — federated results across books, contributors, series and tags, over the shared
 * [com.calypsan.listenup.client.presentation.search.SearchViewModel]'s state.
 *
 * Pure in [state]: everything rendered here comes from the ViewModel, and nothing is fetched or
 * decided in this layer. Renders all five [SearchUiState] variants, each with its own marker so a
 * two-letter [SearchUiState.TooShort] query can never be mistaken for the zero-hit case of
 * [SearchUiState.Results] — that confusion is exactly what `TooShort` exists to prevent (see its
 * KDoc). The field and the type chips render for every state, because they are the page's own
 * controls rather than data about the search — a reader must always be able to keep typing or
 * change scope, including out of an error.
 *
 * **Scope honesty (see the plan's own note):** no facet rail, no sort, no row selection, no bulk
 * bar and no match highlighting. `SearchRepositoryImpl` returns empty facets and never populates
 * [SearchHit.highlight]; inventing any of those here would be a number or a span this page made
 * up. A hit's row shows only the fields it actually carries — [hitMeta] never fabricates one.
 */
@Composable
fun SearchPage(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onToggleType: (SearchHitType) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onRetry: () -> Unit,
) {
    Div(attrs = { classes("search-page") }) {
        Div(attrs = { classes("search-header") }) { H3 { Text("Search") } }

        SearchField(query = state.query, onQueryChanged = onQueryChanged)

        Div(attrs = { classes("search-types") }) {
            SearchHitType.entries.forEach { type ->
                Pill(
                    label = type.label(),
                    selected = type in state.selectedTypes,
                    onClick = { onToggleType(type) },
                )
            }
        }

        if (state is SearchUiState.Results && state.result.isOfflineResult) {
            Div(attrs = { classes("banner", "info") }) { Text("Showing offline results") }
        }

        when (state) {
            is SearchUiState.Idle -> IdlePrompt()
            is SearchUiState.TooShort -> TooShortPrompt()
            is SearchUiState.Searching -> SearchingPrompt()
            is SearchUiState.Error -> ErrorPrompt(message = state.message, onRetry = onRetry)
            is SearchUiState.Results -> ResultsBody(result = state.result, onOpenHit = onOpenHit)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
) {
    Div(attrs = { classes("f-wrap") }) {
        Div(attrs = { classes("f-box") }) {
            Icon(WebIcon.Search, size = SEARCH_FIELD_ICON_SIZE, attrs = { classes("f-ico") })
            Input(type = InputType.Text) {
                classes("f-input")
                value(query)
                attr("placeholder", "Search your library")
                attr("aria-label", "Search")
                onInput { event -> onQueryChanged(event.value) }
            }
        }
    }
}

@Composable
private fun ResultsBody(
    result: SearchResult,
    onOpenHit: (SearchHit) -> Unit,
) {
    if (result.hits.isEmpty()) {
        NoResultsPrompt(query = result.query)
        return
    }
    Div(attrs = { classes("search-summary") }) { Text(summaryText(result)) }
    ResultsList(result = result, onOpenHit = onOpenHit)
}

@Composable
private fun ResultsList(
    result: SearchResult,
    onOpenHit: (SearchHit) -> Unit,
) {
    Div(attrs = { classes("search-results") }) {
        // `hits` is re-grouped on every recomposition otherwise; keyed on the result itself, the
        // same precedent `ContributorsPage.groupByLetter` sets for its own list.
        val grouped = remember(result) { result.hits.groupBy { it.type } }
        SearchHitType.entries.forEach { type ->
            val hits = grouped[type].orEmpty()
            if (hits.isNotEmpty()) {
                Div(attrs = { classes("search-group") }) {
                    Div(attrs = { classes("search-group-h") }) {
                        Span(attrs = { classes("search-group-label") }) { Text(type.label()) }
                        Span(attrs = { classes("search-group-count") }) { Text(hits.size.toString()) }
                    }
                    hits.forEach { hit -> SearchRow(hit = hit, onOpen = { onOpenHit(hit) }) }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    hit: SearchHit,
    onOpen: () -> Unit,
) {
    Div(attrs = {
        classes("search-row")
        tabIndex(0)
        attr("role", "button")
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onOpen()
            }
        }
        onClick { onOpen() }
    }) {
        if (hit.type == SearchHitType.BOOK) {
            Cover(
                title = hit.name,
                imageUrl = coverUrl(hit.id, hit.coverHash, SEARCH_COVER_RUNG),
                size = SEARCH_BADGE_SIZE,
                radius = SEARCH_BADGE_RADIUS,
            )
        } else {
            Div(attrs = {
                classes("search-badge")
                attr("aria-hidden", "true")
            }) { Icon(badgeIconFor(hit.type), size = SEARCH_BADGE_ICON_SIZE) }
        }

        Div(attrs = { classes("search-info") }) {
            Div(attrs = { classes("search-name") }) { Text(hit.name) }
            hitMeta(hit)?.let { meta -> Div(attrs = { classes("search-meta") }) { Text(meta) } }
        }

        Div(attrs = { classes("search-chevron") }) { Icon(WebIcon.ChevronRight, size = SEARCH_CHEVRON_SIZE) }
    }
}

@Composable
private fun IdlePrompt() {
    Prompt(
        marker = "is-idle",
        heading = "Search your library",
        body = "Find books, contributors, series and tags.",
    )
}

@Composable
private fun TooShortPrompt() {
    Prompt(
        marker = "is-tooshort",
        heading = "Keep typing",
        body = "Enter at least $MIN_SEARCH_QUERY_LENGTH characters to search.",
    )
}

@Composable
private fun SearchingPrompt() {
    Prompt(marker = "is-searching", heading = "Searching…", body = null)
}

@Composable
private fun NoResultsPrompt(query: String) {
    Prompt(
        marker = "is-noresults",
        heading = "No results for “$query”",
        body = "Try a different search term.",
    )
}

@Composable
private fun ErrorPrompt(
    message: String,
    onRetry: () -> Unit,
) {
    Div(attrs = { classes("empty", "is-error") }) {
        Div(attrs = { classes("ico") }) { Icon(WebIcon.Search, size = PROMPT_ICON_SIZE) }
        H3 { Text("Search failed") }
        P { Text(message) }
        Button(attrs = {
            classes("btn-o")
            attr("type", "button")
            onClick { onRetry() }
        }) { Text("Try again") }
    }
}

/** The shape every non-error prompt state takes: a mark, a heading, and an optional body line. */
@Composable
private fun Prompt(
    marker: String,
    heading: String,
    body: String?,
) {
    Div(attrs = { classes("empty", marker) }) {
        Div(attrs = { classes("ico") }) { Icon(WebIcon.Search, size = PROMPT_ICON_SIZE) }
        H3 { Text(heading) }
        body?.let { P { Text(it) } }
    }
}

private fun summaryText(result: SearchResult): String {
    val count = if (result.total == 1) "1 result" else "${result.total} results"
    return "$count for “${result.query}”"
}

/**
 * Every field this hit actually carries, joined into one line — never a field it doesn't. Most
 * contributor and series hits currently carry none of these ([SearchRepositoryImpl] leaves
 * `bookCount` null for both), so their rows show only the name, honestly.
 */
private fun hitMeta(hit: SearchHit): String? =
    listOfNotNull(
        hit.subtitle,
        hit.author,
        hit.narrator?.let { "read by $it" },
        hit.seriesName,
        hit.formatDuration(),
        hit.bookCount?.let(::bookCountLabel),
    ).joinToString(" · ")
        .takeIf { it.isNotBlank() }

private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private fun SearchHitType.label(): String =
    when (this) {
        SearchHitType.BOOK -> "Books"
        SearchHitType.CONTRIBUTOR -> "Contributors"
        SearchHitType.SERIES -> "Series"
        SearchHitType.TAG -> "Tags"
    }

private fun badgeIconFor(type: SearchHitType): WebIcon =
    when (type) {
        SearchHitType.BOOK -> WebIcon.Book
        SearchHitType.CONTRIBUTOR -> WebIcon.Person
        SearchHitType.SERIES -> WebIcon.Bookmark
        SearchHitType.TAG -> WebIcon.Hash
    }

private const val SEARCH_FIELD_ICON_SIZE = 19

private const val PROMPT_ICON_SIZE = 24

private const val SEARCH_BADGE_SIZE = 44

private const val SEARCH_BADGE_RADIUS = 12

private const val SEARCH_BADGE_ICON_SIZE = 20

private const val SEARCH_CHEVRON_SIZE = 20

/** Twice [SEARCH_BADGE_SIZE], so a book cover stays sharp on a 2x display. */
private const val SEARCH_COVER_RUNG = 88
