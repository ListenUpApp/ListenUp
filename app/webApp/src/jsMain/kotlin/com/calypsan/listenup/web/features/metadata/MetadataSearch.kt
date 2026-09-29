package com.calypsan.listenup.web.features.metadata

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.SearchLoadState
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Phase one: find the edition.
 *
 * The query is seeded from the book itself, so the reader usually only has to press Search. The
 * region matters more than it looks: the same book is a different ASIN in every Audible market,
 * and the wrong market is the most common reason a search comes back empty.
 */
@Composable
internal fun MetadataSearchPhase(
    state: MetadataUiState.Search,
    onQuery: (String) -> Unit,
    onRegion: (MetadataLocale) -> Unit,
    onSearch: () -> Unit,
    onSelectMatch: (MetadataBook) -> Unit,
) {
    Div(attrs = { classes("mdx-ctx") }) {
        Span(attrs = { classes("mdx-ctx-l") }) { Text("Matching") }
        Span(attrs = { classes("mdx-ctx-t") }) { Text(state.context.currentTitle) }
        if (state.context.currentAuthor.isNotBlank()) {
            Span(attrs = { classes("mdx-ctx-a") }) { Text("by ${state.context.currentAuthor}") }
        }
    }

    // A real <form>, so Enter searches. A wizard whose first step needs a mouse to advance is a
    // wizard people abandon at step one.
    Form(attrs = {
        classes("mdx-search")
        onSubmit { event ->
            event.preventDefault()
            onSearch()
        }
    }) {
        Field(
            label = "Search Audible",
            value = state.query,
            onInput = onQuery,
            leading = WebIcon.Search,
            placeholder = "Title, author, narrator, or ASIN...",
            id = "mdx-query",
        )
        Button(
            kind = ButtonKind.Primary,
            submit = true,
            enabled = !(state.loadState is SearchLoadState.InFlight || state.query.isBlank()),
        ) { Text(if (state.loadState is SearchLoadState.InFlight) "Searching…" else "Search Audible") }
    }

    RegionSelector(state.region, onRegion)

    when (val load = state.loadState) {
        SearchLoadState.Idle -> {
            EmptyState(title = "Enter a title, author, narrator, or ASIN to search.", look = EmptyLook.Inline)
        }

        SearchLoadState.InFlight -> {
            Div(attrs = { classes("skel", "mdx-skel") })
        }

        is SearchLoadState.Failed -> {
            P(attrs = {
                classes("mdx-err")
                attr("role", "alert")
            }) { Text(load.message) }
        }

        is SearchLoadState.Loaded -> {
            if (load.results.isEmpty()) {
                // ⛔ Names the region. An empty result is usually the wrong market, not a
                // missing book, and "try again" without saying that sends people nowhere.
                EmptyState(
                    title = "No matches found",
                    body = "Try a different search term, or another region than ${state.region.displayName}.",
                    look = EmptyLook.Inset,
                )
            } else {
                P(attrs = { classes("mdx-count") }) { Text(matchCount(load.results.size)) }
                Div(attrs = {
                    classes("mdx-results")
                    attr("role", "list")
                }) {
                    load.results.forEach { result ->
                        key(result.asin) {
                            ResultRow(result) { onSelectMatch(result) }
                        }
                    }
                }
            }
        }
    }
}

/** One candidate edition. Everything on it is what tells two editions of the same book apart. */
@Composable
private fun ResultRow(
    result: MetadataBook,
    onSelect: () -> Unit,
) {
    Button(attrs = {
        classes("mdx-result")
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { onSelect() }
    }) {
        // ⛔ `referrerpolicy`: these are Audible's own image hosts, and a referrer carrying this
        // server's address is a detail of the reader's library leaking to a third party.
        result.coverUrl?.let { url ->
            Img(src = url, alt = "", attrs = {
                classes("mdx-result-c")
                attr("loading", "lazy")
                attr("referrerpolicy", "no-referrer")
            })
        } ?: Div(attrs = { classes("mdx-result-c", "mdx-result-none") }) { Icon(WebIcon.Book, size = COVER_ICON) }

        Div(attrs = { classes("mdx-result-m") }) {
            Div(attrs = { classes("mdx-result-t") }) { Text(result.title) }
            result.subtitle?.takeIf { it.isNotBlank() }?.let {
                Div(attrs = { classes("mdx-result-s") }) { Text(it) }
            }
            if (result.authors.isNotEmpty()) {
                Div(attrs = { classes("mdx-result-by") }) {
                    Text("by ${result.authors.joinToString(", ") { it.name }}")
                }
            }
            if (result.narrators.isNotEmpty()) {
                Div(attrs = { classes("mdx-result-n") }) {
                    Text("Narrated by ${result.narrators.joinToString(", ") { it.name }}")
                }
            }
            Div(attrs = { classes("mdx-result-meta") }) {
                result.runtimeMinutes?.let { Span { Text(runtimeLabel(it)) } }
                result.releaseDate?.takeIf { it.isNotBlank() }?.let { Span { Text(it) } }
                Span(attrs = { classes("mdx-asin") }) { Text(result.asin) }
            }
        }
    }
}

/** The Audible markets a search can run against. */
@Composable
internal fun RegionSelector(
    selected: MetadataLocale,
    onRegion: (MetadataLocale) -> Unit,
) {
    Div(attrs = { classes("mdx-regions") }) {
        Span(attrs = { classes("mdx-regions-l") }) { Text("Region") }
        MetadataLocale.SUPPORTED.forEach { region ->
            Button(attrs = {
                classes("pill")
                if (region == selected) classes("on")
                attr(ATTR_TYPE, VALUE_BUTTON)
                attr("aria-pressed", (region == selected).toString())
                onClick { onRegion(region) }
            }) { Text(region.displayName) }
        }
    }
}

/** "1 match found" / "12 matches found" — the plural is not a detail on a page about accuracy. */
internal fun matchCount(count: Int): String = if (count == 1) "1 match found" else "$count matches found"

/** Audible reports a runtime in minutes; readers think in hours. */
internal fun runtimeLabel(minutes: Int): String {
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

private const val MINUTES_PER_HOUR = 60

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val COVER_ICON = 20
