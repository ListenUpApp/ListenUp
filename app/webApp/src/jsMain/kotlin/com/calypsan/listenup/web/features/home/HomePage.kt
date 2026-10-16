package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.WeekChartColumn
import com.calypsan.listenup.client.presentation.home.genreShareBars
import com.calypsan.listenup.client.presentation.home.weekChartColumns
import com.calypsan.listenup.web.design.UnderHeading
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.press
import com.calypsan.listenup.web.features.library.LibraryStatus
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Home — the root route, and the first thing a reader sees after signing in.
 *
 * Renders two independent upstreams side by side: [state] carries the greeting, what you are part
 * way through, and whether the library is still arriving; [stats] carries the last 7 days' listening.
 * They load and fail separately on purpose (see [HomeSession]), so a slow stats query never holds
 * back the row someone actually opened this page for.
 *
 * Pure, like every other page here: no routing decisions, no session lifetime. Every gesture leaves
 * as one of the callbacks.
 *
 * **Deliberately not rendered**, because the data does not back it or the destination does not
 * exist:
 * - No "See all" over Continue Listening. There is no in-progress screen to see all of, and the
 *   Compose clients do not offer one either — the design sheet's action is a canvas convenience.
 * - No "See all" over My Shelves. Every shelf the reader owns is already in the row, so the
 *   control would lead to a longer version of a complete list. The section itself arrived with the
 *   shelf screens it needed.
 */
@Composable
fun HomePage(
    state: HomeUiState,
    stats: HomeStatsUiState,
    actions: HomeActions,
    isWide: Boolean = false,
    selection: BookSelection? = null,
) {
    Div(attrs = { classes("home") }) {
        when (state) {
            is HomeUiState.Loading -> {
                // Deliberately silent about WHY: before the first emission the page genuinely does
                // not know whether the library is empty, syncing or broken, and guessing out loud is
                // how a healthy first run ends up reading like a failure. The greeting's place is
                // held by a skeleton, under an H1 that still names the page.
                PageHeader(title = HOME, documentTitle = null, display = true, pending = true)
            }

            is HomeUiState.Error -> {
                PageHeader(title = HOME, documentTitle = null)
                EmptyState(title = "Home is unavailable", body = state.error.message) {
                    Button(kind = ButtonKind.Primary, onClick = { actions.onRetry() }) { Text("Try again") }
                }
            }

            is HomeUiState.Ready -> {
                PageHeader(title = homeGreeting(state.timeOfDay, state.firstName), documentTitle = null, display = true)
                LibraryStatus(scan = state.scanProgress, isBuilding = state.isBuildingInitialLibrary)
                if (state.alsoInProgress.isNotEmpty()) AlsoInProgress(state.alsoInProgress, isWide, actions, selection)
                MyShelves(state.myShelves, actions.onOpenShelf, actions.onCreateShelf)
                LastSevenDays(stats, actions)
            }
        }
    }
}

private const val PERCENT = 100

/**
 * The reader's own shelves.
 *
 * Cut from Home's first version because there was nowhere for a card to lead; it arrives now with
 * the shelf screens. The data never went anywhere — `HomeUiState.Ready.myShelves` has been
 * populated since the beginning, which is why this is a rendering change and not a plumbing one.
 *
 * The empty state offers to make the first shelf rather than explaining what shelves are: someone
 * who has none learns more from making one than from a paragraph about them.
 */
@Composable
private fun MyShelves(
    shelves: List<Shelf>,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
) {
    Div(attrs = { classes("home-section") }) {
        Div(attrs = { classes("home-section-row") }) {
            H2(attrs = { classes("home-section-h") }) { Text("My shelves") }
            Button(kind = ButtonKind.Secondary, onClick = { onCreateShelf() }) { Text("New shelf") }
        }
        UnderHeading(level = SECTION_HEADING_LEVEL) {
            if (shelves.isEmpty()) {
                EmptyState(
                    title = "No shelves yet",
                    body = "A shelf is a way to group books — a series, a mood, a plan for the winter.",
                )
            } else {
                Div(attrs = { classes("home-shelves") }) {
                    shelves.forEach { shelf ->
                        key(shelf.idString) {
                            Button(attrs = {
                                classes("home-shelf")
                                attr(ATTR_TYPE, VALUE_BUTTON)
                                onClick { onOpenShelf(shelf.idString) }
                            }) {
                                Span(attrs = { classes("home-shelf-t") }) { Text(shelf.name) }
                                Span(attrs = { classes("home-shelf-sub") }) {
                                    Text(bookCountLabel(shelf.bookCount))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val HOME = "Home"
