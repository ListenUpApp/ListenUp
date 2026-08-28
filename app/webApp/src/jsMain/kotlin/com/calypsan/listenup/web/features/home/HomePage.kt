package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.WeekChartColumn
import com.calypsan.listenup.client.presentation.home.genreShareBars
import com.calypsan.listenup.client.presentation.home.weekChartColumns
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The shared empty-state block, borrowed rather than restyled per section. */
private const val EMPTY_CLASS = "empty"

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
) {
    Div(attrs = { classes("home") }) {
        when (state) {
            is HomeUiState.Loading -> {
                HomeSkeleton()
            }

            is HomeUiState.Error -> {
                Div(attrs = { classes(EMPTY_CLASS) }) {
                    H3 { Text("Home is unavailable") }
                    P { Text(state.message) }
                }
            }

            is HomeUiState.Ready -> {
                HomeHeader(greeting = state.greeting, onOpenSearch = onOpenSearch)
                LibraryStatus(state)
                ContinueListening(state.continueListening, onOpenBook, onOpenLibrary)
                MyShelves(state.myShelves, onOpenShelf, onCreateShelf)
                ThisWeek(stats)
            }
        }
    }
}

/**
 * The pre-first-emission state. Deliberately silent about WHY: at this point the page genuinely
 * does not know whether the library is empty, syncing or broken, and guessing out loud is how a
 * healthy first run ends up reading like a failure.
 */
@Composable
private fun HomeSkeleton() {
    Div(attrs = { classes("home-header") }) {
        Div(attrs = { classes("home-greet") }) {
            Div(attrs = { classes("skel", "home-skel-line") })
            Div(attrs = { classes("skel", "home-skel-name") })
        }
    }
}

@Composable
private fun HomeHeader(
    greeting: String,
    onOpenSearch: () -> Unit,
) {
    Div(attrs = { classes("home-header") }) {
        Div(attrs = { classes("home-greet") }) {
            H1 { Text(greeting) }
        }
        // A search affordance on the landing page, even though the sidebar and ⌘K both reach the
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
            Span(attrs = { classes("kbd") }) { Text("⌘K") }
        }
    }
}

private const val SEARCH_ICON_SIZE = 18

/**
 * The strip that says the library is still arriving.
 *
 * This has no counterpart in the design sheet, and it is the one section web needs most: a first
 * sync runs for minutes in a browser, and without this the page is a greeting above an empty row
 * with no explanation.
 *
 * ⛔ It reads `isBuildingInitialLibrary`, **not** `isSyncing`. `isSyncing` tracks the connection,
 * which is `Connected` for the whole of an initial seed — so it is *false* during precisely the
 * window this strip exists for, and driving the strip from it would show nothing at all while
 * thousands of books stream in. `LibraryUiState.Loaded` carries the same warning; Home's empty
 * shelf would have told the same lie its empty grid used to.
 *
 * A scan outranks the seed when both are live, because
 * [com.calypsan.listenup.client.domain.model.ScanProgressState] can say what is actually happening
 * and how far along it is.
 */
@Composable
private fun LibraryStatus(state: HomeUiState.Ready) {
    val scan = state.scanProgress
    when {
        scan != null -> {
            Div(attrs = { classes("home-status") }) {
                Span(attrs = { classes("home-status-t") }) { Text(scan.phaseDisplayName) }
                scan.progressFraction?.let { fraction ->
                    Div(attrs = { classes("home-status-track") }) {
                        Div(attrs = {
                            classes("home-status-fill")
                            style { property("width", "${(fraction * PERCENT).toInt()}%") }
                        })
                    }
                }
                scan.changesSummary?.let { summary ->
                    Span(attrs = { classes("home-status-sub") }) { Text(summary) }
                }
            }
        }

        state.isBuildingInitialLibrary -> {
            Div(attrs = { classes("home-status") }) {
                Span(attrs = { classes("home-status-t") }) { Text("Building your library…") }
                Span(attrs = { classes("home-status-sub") }) { Text("Books appear as they arrive.") }
            }
        }

        else -> {
            Unit
        }
    }
}

private const val PERCENT = 100

@Composable
private fun ContinueListening(
    items: List<ContinueListeningItem>,
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("home-section") }) {
        H3(attrs = { classes("home-section-h") }) { Text("Continue listening") }
        if (items.isEmpty()) {
            Div(attrs = { classes(EMPTY_CLASS) }) {
                H3 { Text("Nothing on the go") }
                P { Text("Start a book and it will wait for you here.") }
                Button(attrs = {
                    classes("btn")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onOpenLibrary() }
                }) { Text("Browse library") }
            }
        } else {
            Div(attrs = { classes("home-continue") }) {
                items.forEach { item -> ContinueCard(item, onOpenBook) }
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
            Div(attrs = {
                classes("home-card")
                tabIndex(0)
                attr("role", "button")
                onKeyDown { event ->
                    if (event.key == "Enter" || event.key == " ") {
                        event.preventDefault()
                        onOpenBook(book.bookId)
                    }
                }
                onClick { onOpenBook(book.bookId) }
            }) {
                Cover(
                    title = book.title,
                    imageUrl = coverUrl(book.bookId, book.coverHash, width = CONTINUE_COVER_WIDTH),
                    size = CONTINUE_COVER_WIDTH,
                )
                Div(attrs = { classes("home-card-progress") }) {
                    Div(attrs = {
                        classes("home-card-progress-fill")
                        style { property("width", "${book.progressPercent}%") }
                    })
                }
                Span(attrs = { classes("home-card-t") }) { Text(book.title) }
                Span(attrs = { classes("home-card-sub") }) { Text(book.timeRemainingFormatted) }
            }
        }
    }
}

@Composable
private fun ThisWeek(stats: HomeStatsUiState) {
    Div(attrs = { classes("home-section") }) {
        H3(attrs = { classes("home-section-h") }) { Text("This week") }
        when (stats) {
            is HomeStatsUiState.Loading -> {
                Div(attrs = { classes("skel", "home-stats-skel") })
            }

            is HomeStatsUiState.Empty -> {
                Div(attrs = { classes(EMPTY_CLASS) }) {
                    H3 { Text("No listening yet") }
                    P { Text("Your week fills in as you listen.") }
                }
            }

            is HomeStatsUiState.Error -> {
                Div(attrs = { classes(EMPTY_CLASS) }) {
                    H3 { Text("Stats are unavailable") }
                    P {
                        Text(
                            if (stats.isRetryable) {
                                "This usually fixes itself — check back shortly."
                            } else {
                                "Your listening history could not be read."
                            },
                        )
                    }
                }
            }

            is HomeStatsUiState.Data -> {
                StatsCard(stats)
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
            H3(attrs = { classes("home-section-h") }) { Text("My shelves") }
            Button(attrs = {
                classes("btn-o")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onCreateShelf() }
            }) { Text("New shelf") }
        }

        if (shelves.isEmpty()) {
            Div(attrs = { classes(EMPTY_CLASS) }) {
                H3 { Text("No shelves yet") }
                P { Text("A shelf is a way to group books — a series, a mood, a plan for the winter.") }
            }
        } else {
            Div(attrs = { classes("home-shelves") }) {
                shelves.forEach { shelf ->
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

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
