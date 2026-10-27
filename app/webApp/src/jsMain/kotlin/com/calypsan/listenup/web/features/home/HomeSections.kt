package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.WeekChartColumn
import com.calypsan.listenup.client.presentation.home.genreShareBars
import com.calypsan.listenup.client.presentation.home.weekChartColumns
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LinkButton
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.UnderHeading
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.press
import com.calypsan.listenup.web.features.shelf.ShelfCard
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The last bar's label (spec §2.6), sharedUI's `home_today` verbatim: the others keep their weekday letter. */
private const val TODAY_LABEL = "Today"

/** Spec §2.5 caps: desktop one row of up to six; the phone CSS shows the first four (C10). */
internal const val EVERYTHING_IN_PROGRESS_CAP = 6

/** The cover rung a card asks the server for, in px. */
private const val CARD_COVER_RUNG = 168

private const val PERCENT = 100

private const val STREAK_ICON_SIZE = 16

private const val SKELETON_SHELVES = 2

private const val ARIA_LABEL = "aria-label"

/** A section's own heading is an H2, so anything headed inside it is an H3. */
internal const val SECTION_HEADING_LEVEL = 2

/** "Everything in progress", the loaded book first, with its header link. Absent when nothing is in progress (§2.1). */
@Composable
internal fun EverythingInProgress(
    items: List<ContinueListeningItem>,
    isWide: Boolean,
    actions: HomeActions,
    selection: BookSelection?,
) {
    Section(attrs = {
        classes("home-section", "home-progress")
        attr("aria-labelledby", "home-progress-h")
    }) {
        Div(attrs = { classes("home-section-row") }) {
            H2(attrs = {
                id("home-progress-h")
                classes("home-section-h", "home-progress-h")
            }) { Text(HomeWords.EVERYTHING_IN_PROGRESS) }
            Div(attrs = { classes("home-section-actions") }) {
                // The way into selection on web (H16): a pointer has no long-press. Hidden while selecting — the bulk
                // bar's Done ends it.
                if (selection != null && !selection.isSelecting) {
                    Button(kind = ButtonKind.Secondary, onClick = selection.onStart) { Text(HomeWords.SELECT) }
                }
                LinkButton(label = "In Library →", onClick = actions.onOpenInProgress)
            }
        }
        Div(attrs = { classes("home-continue") }) {
            items.take(EVERYTHING_IN_PROGRESS_CAP).forEach { item ->
                key(item.bookId) { HomeCard(item, isWide, actions.onOpenBook, selection) }
            }
        }
    }
}

/**
 * A Home card (§2.5): square art with the coral line on its bottom edge and the lock, then title, author, "Read by …"
 * on wide pages, and the time left. One `aria-label` names it (C8, §8.2); the line is `aria-hidden`.
 *
 * A [ContinueListeningItem.Loading] keeps its place as a skeleton of the SAME size rather than being dropped: its
 * position row has already arrived, so the book is genuinely coming, and hiding it would shrink the row and then grow
 * it again mid-sync.
 */
@Composable
private fun HomeCard(
    item: ContinueListeningItem,
    isWide: Boolean,
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
            ReadyCard(item.book, isWide, onOpenBook, selection)
        }
    }
}

@Composable
private fun ReadyCard(
    book: ContinueListeningBook,
    isWide: Boolean,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    val selecting = selection?.isSelecting == true
    val isSelected = selection?.isSelected(book.bookId) == true
    // While selecting, a press picks instead of opening — one gesture, two jobs, decided by the mode, exactly as the
    // library grid does it.
    val press = { selection.press(book.bookId) { onOpenBook(book.bookId) } }
    Div(attrs = {
        classes("home-card")
        if (isSelected) classes("is-sel")
        tabIndex(0)
        attr("role", if (selecting) "checkbox" else "button")
        if (selecting) attr("aria-checked", isSelected.toString())
        attr(ARIA_LABEL, homeCardLabel(book, withNarrator = isWide))
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
            imageUrl = coverUrl(book.bookId, book.coverHash, width = CARD_COVER_RUNG),
            size = null,
            decorative = true,
            overlay = {
                RestrictedMarker(book.bookId)
                ProgressBar(
                    value = book.progressPercent / PERCENT.toFloat(),
                    look = ProgressLook.Overlay,
                    decorative = true,
                )
            },
        )
        Span(attrs = { classes("home-card-t") }) { Text(book.title) }
        if (book.authorNames.isNotBlank()) Span(attrs = { classes("home-card-a") }) { Text(book.authorNames) }
        if (isWide) {
            book.narratorNames?.takeIf { it.isNotBlank() }?.let { names ->
                Span(attrs = { classes("home-card-n") }) { Text("Read by $names") }
            }
        }
        Span(attrs = { classes("home-card-sub") }) { Text(timeLeftText(book.timeRemainingMs)) }
    }
}

/** "18h 40m left" — Library's wording on every card, whatever its length (open question 6: no "Almost done"). */
internal fun timeLeftText(remainingMs: Long): String =
    "${DurationFormatter.hoursMinutes(remainingMs.milliseconds)} left"

/** "Babel, by R. F. Kuang, 11% listened, 18h 40m left" (§2.5); wide pages add ", read by …". */
internal fun homeCardLabel(
    book: ContinueListeningBook,
    withNarrator: Boolean,
): String =
    listOfNotNull(
        book.title,
        book.authorNames.takeIf { it.isNotBlank() }?.let { "by $it" },
        book.narratorNames?.takeIf { withNarrator && it.isNotBlank() }?.let { "read by $it" },
        "${book.progressPercent}% listened",
        timeLeftText(book.timeRemainingMs),
    ).joinToString(", ")

/**
 * "Your last 7 days" (spec §2.6): one card with its heading inside. Loading is a skeleton of the data's size, a new
 * listener gets a one-line invitation, and a failure says so with **Try again** (gap 17).
 */
@Composable
internal fun LastSevenDays(
    stats: HomeStatsUiState,
    actions: HomeActions,
) {
    Section(attrs = {
        classes("home-stats-card")
        attr("aria-labelledby", "home-stats-h")
    }) {
        H2(attrs = {
            id("home-stats-h")
            classes("home-stats-h")
        }) { Text(HomeWords.YOUR_LAST_7_DAYS) }
        UnderHeading(level = SECTION_HEADING_LEVEL) {
            when (stats) {
                is HomeStatsUiState.Loading -> {
                    Div(attrs = { classes("skel", "home-stats-skel") })
                }

                is HomeStatsUiState.Empty -> {
                    // A one-line invitation, never a zeroed week (spec §2.6, gap 6).
                    P(attrs = { classes("home-stats-invite") }) { Text(HomeWords.STATS_INVITATION_BODY) }
                }

                is HomeStatsUiState.Error -> {
                    StatsError(stats, actions.onRetryStats)
                }

                is HomeStatsUiState.Data -> {
                    StatsBody(stats, actions.onOpenLeaderboard)
                }
            }
        }
    }
}

@Composable
private fun StatsError(
    stats: HomeStatsUiState.Error,
    onRetry: () -> Unit,
) {
    if (stats.isRetryable) {
        EmptyState(title = HomeWords.COULDNT_LOAD_STATS) {
            Button(kind = ButtonKind.Secondary, onClick = { onRetry() }) { Text(HomeWords.TRY_AGAIN) }
        }
    } else {
        EmptyState(title = HomeWords.COULDNT_LOAD_STATS)
    }
}

@Composable
private fun StatsBody(
    stats: HomeStatsUiState.Data,
    onOpenLeaderboard: () -> Unit,
) {
    val streak = stats.streak
    Div(attrs = { classes("home-stats-top") }) {
        Span(attrs = { classes("home-stats-total") }) { Text(stats.formattedListenTime) }
        streak.chipDays?.let { days ->
            Span(attrs = { classes("home-streak-chip") }) {
                Icon(WebIcon.Flame, size = STREAK_ICON_SIZE)
                Text("$days-day streak")
            }
        }
    }
    WeekChart(weekChartColumns(stats.dailyBuckets), stats.maxDailySeconds)
    if (stats.hasGenreData) TopGenres(stats)
    Div(attrs = { classes("home-stats-foot") }) {
        Span(attrs = { classes("home-stats-best") }) {
            streak.bestDays?.let { Text(if (it == 1) "Best streak 1 day" else "Best streak $it days") }
        }
        LinkButton(label = "7-day leaderboard →", onClick = onOpenLeaderboard)
    }
}

/** "Listening per day, oldest to today: 1h 10m, …, today 1m" (C15): the chart's words, for one image. */
internal fun weekChartLabel(columns: List<WeekChartColumn>): String =
    "Listening per day, oldest to today: " +
        columns.joinToString(", ") { column ->
            val amount = DurationFormatter.hoursMinutes(column.totalSeconds.seconds)
            if (column.isToday) "today $amount" else amount
        }

@Composable
private fun WeekChart(
    columns: List<WeekChartColumn>,
    maxSeconds: Long,
) {
    // Scale against the busiest day, floored at 1 so an all-zero week divides cleanly and every
    // column falls back to the empty nub rather than a full-height bar.
    val scale = maxSeconds.coerceAtLeast(1L).toDouble()
    Div(attrs = {
        classes("home-chart")
        attr("role", "img")
        attr(ARIA_LABEL, weekChartLabel(columns))
    }) {
        columns.forEach { column ->
            Div(attrs = {
                classes("home-chart-col")
                attr("aria-hidden", "true")
            }) {
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
                }) { Text(if (column.isToday) TODAY_LABEL else column.label) }
            }
        }
    }
}

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
 * "My shelves" (spec §2.7): the header always offers New shelf, so the first shelf is one press away, and See all once
 * there is a shelf to see (H24); the shelves are a grid of mosaic cards, or, with none yet, a line inviting the first.
 */
@Composable
internal fun MyShelves(
    shelves: List<Shelf>,
    actions: HomeActions,
    isLoading: Boolean = false,
) {
    Section(attrs = {
        classes("home-section", "home-shelves-section")
        attr("aria-labelledby", "home-shelves-h")
    }) {
        Div(attrs = { classes("home-section-row") }) {
            H2(attrs = {
                id("home-shelves-h")
                classes("home-section-h")
            }) { Text("My shelves") }
            Div(attrs = { classes("home-section-actions") }) {
                if (!isLoading && shelves.isNotEmpty()) {
                    Button(
                        kind = ButtonKind.Ghost,
                        onClick = actions.onSeeAllShelves,
                        label = HomeWords.SEE_ALL_SHELVES,
                    ) {
                        Text(HomeWords.SEE_ALL)
                    }
                }
                Button(kind = ButtonKind.Secondary, onClick = { actions.onCreateShelf() }) { Text("New shelf") }
            }
        }
        if (isLoading) {
            // The library is still arriving: no shelves yet is not yet the reader's, so no invitation (spec §2.1).
            Div(attrs = {
                classes("home-shelves")
                attr("role", "img")
                attr(ARIA_LABEL, HomeWords.LOADING_SHELVES)
            }) {
                repeat(SKELETON_SHELVES) { Div(attrs = { classes("skel", "home-shelf-skel") }) }
            }
        } else if (shelves.isEmpty()) {
            P(attrs = { classes("home-shelves-invite") }) {
                Text("A shelf is your own list of audiobooks — a series, a mood, a plan for the winter.")
            }
        } else {
            Div(attrs = { classes("home-shelves") }) {
                shelves.forEach { shelf ->
                    key(shelf.idString) { ShelfCard(shelf, actions.onOpenShelf) }
                }
            }
        }
    }
}
