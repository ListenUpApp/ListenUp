package com.calypsan.listenup.web.features.search

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.web.design.Breadcrumb
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Every hit of one type for one query — what the capped groups on `/search` defer to.
 *
 * ⛔ This page is why the caps are honest. The main search runs with a limit of 30 across all
 * types; this one re-runs the same query for a single type with a limit of 100, so "See all" is a
 * real promise of more rather than a relabelling of the same rows. Without it, capping `/search`
 * would only hide hits.
 */
@Composable
fun SeeAllPage(
    state: SeeAllSearchUiState,
    openableTypes: Set<SearchHitType>,
    onOpenHit: (SearchHit) -> Unit,
    onOpenSearch: () -> Unit,
) {
    Div(attrs = { classes("sall") }) {
        Breadcrumb(trail = listOf("Search", seeAllTitle(state)), onNavigate = { onOpenSearch() })

        when (state) {
            SeeAllSearchUiState.Idle, SeeAllSearchUiState.Loading -> {
                SearchingPrompt()
            }

            SeeAllSearchUiState.TooShort -> {
                TooShortPrompt()
            }

            is SeeAllSearchUiState.Error -> {
                Prompt(marker = "is-error", heading = "Search failed", body = state.message)
            }

            is SeeAllSearchUiState.Results -> {
                if (state.hits.isEmpty()) {
                    NoResultsPrompt(query = state.query)
                    return@Div
                }
                Div(attrs = { classes("sall-h") }) {
                    H1(attrs = { classes("sall-t") }) { Text(state.type.label()) }
                    Span(attrs = { classes("sall-n") }) { Text(hitCountLabel(state.hits.size)) }
                    Span(attrs = { classes("sall-q") }) { Text("for “${state.query}”") }
                }
                Div(attrs = { classes("search-results") }) {
                    state.hits.forEach { hit ->
                        SearchRow(
                            hit = hit,
                            isOpenable = hit.type in openableTypes,
                            onOpen = { onOpenHit(hit) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The trail's last crumb.
 *
 * Every state but [SeeAllSearchUiState.Results] has no type to name — the query is in the URL but
 * the state has not settled on one — so the crumb says what the page is rather than inventing a
 * type it does not know.
 */
internal fun seeAllTitle(state: SeeAllSearchUiState): String =
    if (state is SeeAllSearchUiState.Results) state.type.label() else "All results"

internal fun hitCountLabel(count: Int): String = if (count == 1) "1 result" else "$count results"
