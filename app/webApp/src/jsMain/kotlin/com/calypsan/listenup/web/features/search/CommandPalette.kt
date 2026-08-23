package com.calypsan.listenup.web.features.search

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchUiState
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The ⌘K / Ctrl+K / `/` command palette — a compact, keyboard-first render of the same five
 * [SearchUiState] cases [SearchPage] renders, reusing [SearchField], the [Prompt] family and
 * [ResultsList] rather than forking a second copy of any of them — see their own KDocs.
 *
 * Deliberately narrower than the full page: no type-filter chips (this is a jump tool, not the
 * filterable search experience) and no retry affordance on [SearchUiState.Error] — the palette's
 * own session starts fresh every time it opens, so "try again" is just closing and reopening it.
 *
 * Pure, like [SearchPage]: every keystroke reaches [onQueryChanged] and every row click reaches
 * [onOpenHit], but the palette's own keys — arrows, Enter, Shift+Enter, Escape, and the focus trap
 * — are interpreted by `CommandPaletteHost` in `WebAppRoot.kt`, not here. That keeps this composable
 * a plain function of [state], the same shape [SearchPage] already is, and puts routing decisions
 * where the rest of this app keeps them. [highlighted] is whichever hit the host's keyboard
 * navigation currently has selected, or null when there is nothing to navigate — see
 * [openableSearchHits] for the order it moves through.
 */
@Composable
fun CommandPalette(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    openableTypes: Set<SearchHitType>,
    highlighted: SearchHit?,
) {
    Div(attrs = { classes("cmdk-scrim") }) {
        Div(attrs = {
            classes("cmdk-panel")
            attr("role", "dialog")
            attr("aria-modal", "true")
            attr("aria-label", "Command palette")
        }) {
            Div(attrs = { classes("cmdk-field") }) {
                SearchField(query = state.query, onQueryChanged = onQueryChanged, autoFocus = true)
            }

            Div(attrs = { classes("cmdk-body") }) {
                when (state) {
                    is SearchUiState.Idle -> {
                        IdlePrompt()
                    }

                    is SearchUiState.TooShort -> {
                        TooShortPrompt()
                    }

                    is SearchUiState.Searching -> {
                        SearchingPrompt()
                    }

                    is SearchUiState.Error -> {
                        Prompt(marker = "is-error", heading = "Search failed", body = state.message)
                    }

                    is SearchUiState.Results -> {
                        if (state.result.hits.isEmpty()) {
                            NoResultsPrompt(query = state.result.query)
                        } else {
                            ResultsList(
                                result = state.result,
                                openableTypes = openableTypes,
                                onOpenHit = onOpenHit,
                                highlighted = highlighted,
                            )
                        }
                    }
                }
            }

            CommandPaletteFooter()
        }
    }
}

/** The design's own footer hint row: the four keys the palette answers to, spelled out. */
@Composable
private fun CommandPaletteFooter() {
    Div(attrs = { classes("cmdk-footer") }) {
        CommandPaletteHint("↑↓", "navigate")
        CommandPaletteHint("↵", "open")
        CommandPaletteHint("⇧↵", "full search")
        CommandPaletteHint("esc", "close")
    }
}

@Composable
private fun CommandPaletteHint(
    key: String,
    label: String,
) {
    Span(attrs = { classes("cmdk-hint") }) {
        Span(attrs = { classes("kbd") }) { Text(key) }
        Text(label)
    }
}

/**
 * The ordered, openable-only hit list a keyboard reader can move through — [groupHitsByType]'s own
 * display order, filtered to [openableTypes], so the row `CommandPaletteHost` highlights third
 * after two presses of ↓ is always the third row a reader can actually see. Empty for every
 * [SearchUiState] except a populated [SearchUiState.Results] — [SearchUiState.Searching] carries
 * no result to navigate yet, on purpose.
 */
internal fun openableSearchHits(
    state: SearchUiState,
    openableTypes: Set<SearchHitType>,
): List<SearchHit> {
    val hits = (state as? SearchUiState.Results)?.result?.hits.orEmpty()
    val grouped = groupHitsByType(hits)
    return SearchHitType.entries.flatMap { grouped[it].orEmpty() }.filter { it.type in openableTypes }
}
