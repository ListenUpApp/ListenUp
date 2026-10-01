package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.web.design.paletteShortcutLabel
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

/** Cover size for a Continue Listening card, in px. Square, like the artwork. */
private const val CONTINUE_COVER_WIDTH = 168

/**
 * Home — the root route, and the first thing a reader sees after signing in.
 *
 * Renders two independent upstreams side by side: [state] carries the greeting, what you are part
 * way through, and whether the library is still arriving; [stats] carries this week's listening.
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
    onOpenBook: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
    /** Re-runs a failed Home. Offered only on the failed page — web has no page-level refresh. */
    onRetry: () -> Unit,
    /** Re-runs failed stats, without touching the rest of the page. */
    onRetryStats: () -> Unit,
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
                EmptyState(title = "Home is unavailable", body = state.message) {
                    Button(kind = ButtonKind.Primary, onClick = { onRetry() }) { Text("Try again") }
                }
            }

            is HomeUiState.Ready -> {
                HomeHeader(greeting = state.greeting, onOpenSearch = onOpenSearch)
                LibraryStatus(scan = state.scanProgress, isBuilding = state.isBuildingInitialLibrary)
                ContinueListening(state.continueListening, onOpenBook, onOpenLibrary, selection)
                MyShelves(state.myShelves, onOpenShelf, onCreateShelf)
                ThisWeek(stats, onRetryStats)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    greeting: String,
    onOpenSearch: () -> Unit,
) {
    PageHeader(title = greeting, documentTitle = null, display = true) {
        // A search affordance on the landing page, even though the sidebar and the palette shortcut both reach the
        // same place — this is where someone arrives, and "where do I type?" should not need a
        // shortcut to answer. The hint teaches the shortcut rather than replacing it.
        Button(attrs = {
            classes("home-search")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr("aria-label", "Search your library")
            onClick { onOpenSearch() }
        }) {
            Icon(WebIcon.Search, size = SEARCH_ICON_SIZE)
            Span(attrs = { classes("home-search-label") }) { Text("Search your library") }
            Span(attrs = { classes("kbd") }) { Text(paletteShortcutLabel()) }
        }
    }
}

private const val SEARCH_ICON_SIZE = 18

private const val PERCENT = 100

@Composable
private fun ContinueListening(
    items: List<ContinueListeningItem>,
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    selection: BookSelection?,
) {
    Div(attrs = { classes("home-section") }) {
        H2(attrs = { classes("home-section-h") }) { Text("Continue listening") }
        UnderHeading(level = SECTION_HEADING_LEVEL) {
            if (items.isEmpty()) {
                EmptyState(title = "Nothing on the go", body = "Start a book and it will wait for you here.") {
                    Button(kind = ButtonKind.Primary, onClick = { onOpenLibrary() }) { Text("Browse library") }
                }
            } else {
                Div(attrs = { classes("home-continue") }) {
                    items.forEach { item ->
                        key(item.bookId) {
                            ContinueCard(item, onOpenBook, selection)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One Continue Listening card.
 *
 * A [ContinueListeningItem.Loading] renders a skeleton of the SAME size in the SAME slot rather
 * than being dropped: its position row has already arrived, so the book is genuinely coming, and
 * hiding it would shrink the row and then grow it again mid-sync.
 */
@Composable
private fun ContinueCard(
    item: ContinueListeningItem,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    when (item) {
        is ContinueListeningItem.Loading -> {
            Div(attrs = { classes("home-card", "is-loading") }) {
                Div(attrs = { classes("skel", "home-card-cover-skel") })
                Div(attrs = { classes("skel", "home-skel-line") })
            }
        }

        is ContinueListeningItem.Ready -> {
            val book = item.book
            // While selecting, a press picks instead of opening — one gesture, two jobs, decided
            // by the mode, exactly as the library grid does it.
            val press = { selection.press(book.bookId) { onOpenBook(book.bookId) } }
            Div(attrs = {
                classes("home-card")
                if (selection?.isSelected(book.bookId) == true) classes("is-sel")
                tabIndex(0)
                attr("role", "button")
                onKeyDown { event ->
                    if (event.key == "Enter" || event.key == " ") {
                        event.preventDefault()
                        press()
                    }
                }
                onClick { press() }
            }) {
                Cover(
                    title = book.title,
                    imageUrl = coverUrl(book.bookId, book.coverHash, width = CONTINUE_COVER_WIDTH),
                    size = CONTINUE_COVER_WIDTH,
                    decorative = true,
                )
                // The time left under the title says it in words; the rail is its picture.
                ProgressBar(
                    value = book.progressPercent / PERCENT.toFloat(),
                    look = ProgressLook.Rail,
                    decorative = true,
                    attrs = { classes("home-card-progress") },
                )
                Span(attrs = { classes("home-card-t") }) { Text(book.title) }
                Span(attrs = { classes("home-card-sub") }) { Text(book.timeRemainingFormatted) }
            }
        }
    }
}

@Composable
private fun ThisWeek(
    stats: HomeStatsUiState,
    onRetry: () -> Unit,
) {
    Div(attrs = { classes("home-section") }) {
        H2(attrs = { classes("home-section-h") }) { Text("This week") }
        UnderHeading(level = SECTION_HEADING_LEVEL) {
            when (stats) {
                is HomeStatsUiState.Loading -> {
                    Div(attrs = { classes("skel", "home-stats-skel") })
                }

                is HomeStatsUiState.Empty -> {
                    EmptyState(title = "No listening yet", body = "Your week fills in as you listen.")
                }

                is HomeStatsUiState.Error -> {
                    if (stats.isRetryable) {
                        EmptyState(title = "Stats are unavailable", body = "This week could not be read just now.") {
                            Button(kind = ButtonKind.Secondary, onClick = { onRetry() }) { Text("Try again") }
                        }
                    } else {
                        EmptyState(title = "Stats are unavailable", body = "Your listening history could not be read.")
                    }
                }

                is HomeStatsUiState.Data -> {
                    StatsCard(stats)
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: HomeStatsUiState.Data) {
    Div(attrs = { classes("home-stats") }) {
        Div(attrs = { classes("home-stats-main") }) {
            Span(attrs = { classes("home-stats-total") }) { Text(stats.formattedListenTime) }
            Span(attrs = { classes("home-stats-unit") }) { Text("listened") }
            WeekChart(weekChartColumns(stats.dailyBuckets), stats.maxDailySeconds)
        }
        Div(attrs = { classes("home-stats-side") }) {
            if (stats.hasStreak) Streak(stats.currentStreakDays, stats.longestStreakDays)
            if (stats.hasGenreData) TopGenres(stats)
        }
    }
}

@Composable
private fun WeekChart(
    columns: List<WeekChartColumn>,
    maxSeconds: Long,
) {
    // Scale against the busiest day, floored at 1 so an all-zero week divides cleanly and every
    // column falls back to the empty nub rather than a full-height bar.
    val scale = maxSeconds.coerceAtLeast(1L).toDouble()
    Div(attrs = { classes("home-chart") }) {
        columns.forEach { column ->
            Div(attrs = { classes("home-chart-col") }) {
                Div(attrs = {
                    classes("home-bar")
                    if (column.isToday) classes("is-today")
                    if (column.totalSeconds <= 0L) classes("is-empty")
                    style {
                        property("height", "${(column.totalSeconds / scale * PERCENT).toInt()}%")
                    }
                })
                Span(attrs = {
                    classes("home-bar-label")
                    if (column.isToday) classes("is-today")
                }) { Text(column.label) }
            }
        }
    }
}

@Composable
private fun Streak(
    currentDays: Int,
    longestDays: Int,
) {
    Div(attrs = { classes("home-streak") }) {
        Span(attrs = { classes("home-streak-mark") }) { Icon(WebIcon.Flame, size = STREAK_ICON_SIZE) }
        Div(attrs = { classes("home-streak-text") }) {
            Span(attrs = { classes("home-streak-t") }) { Text("$currentDays-day streak") }
            Span(attrs = { classes("home-streak-sub") }) { Text("Best: $longestDays days") }
        }
    }
}

private const val STREAK_ICON_SIZE = 22

@Composable
private fun TopGenres(stats: HomeStatsUiState.Data) {
    Div(attrs = { classes("home-genres") }) {
        Span(attrs = { classes("home-genres-h") }) { Text("Top genres") }
        genreShareBars(stats.topGenres).forEach { bar ->
            key(bar.genreName) {
                Div(attrs = { classes("home-genre") }) {
                    Span(attrs = { classes("home-genre-name") }) { Text(bar.genreName) }
                    Div(attrs = { classes("home-genre-track") }) {
                        Div(attrs = {
                            classes("home-genre-fill")
                            style { property("width", "${bar.percent}%") }
                        })
                    }
                    Span(attrs = { classes("home-genre-pct") }) { Text("${bar.percent}%") }
                }
            }
        }
    }
}

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

/** A section's own heading is an H2, so anything headed inside it is an H3. */
private const val SECTION_HEADING_LEVEL = 2
