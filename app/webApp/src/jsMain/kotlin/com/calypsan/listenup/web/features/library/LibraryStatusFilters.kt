package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Pill
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_HOUR = 3_600_000L

/** Status filters with whole-library counts (board `Library-WebDesktop`): one row, scrolling on a phone. */
@Composable
internal fun StatusFilterRow(
    selected: BookStatusFilter,
    counts: BookStatusCounts,
    onSelect: (BookStatusFilter) -> Unit,
) {
    Div(attrs = {
        classes("lib-filters")
        attr("role", "group")
        attr("aria-label", "Show")
    }) {
        BookStatusFilter.entries.forEach { filter ->
            Pill(
                label = "${filter.webLabel()} · ${groupedCount(counts.countFor(filter))}",
                selected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

/** The library has books, but [filter] matches none of them: say so, and offer the way back. */
@Composable
internal fun FilteredEmpty(
    filter: BookStatusFilter,
    onShowAll: () -> Unit,
) {
    EmptyState(title = filteredEmptyCopy(filter), marker = "lib-filtered-empty") {
        Button(kind = ButtonKind.Secondary, onClick = onShowAll, attrs = { classes("lib-show-all") }) {
            Text("Show all books")
        }
    }
}

internal fun filteredEmptyCopy(filter: BookStatusFilter): String =
    when (filter) {
        BookStatusFilter.IN_PROGRESS -> "Nothing in progress right now."
        BookStatusFilter.NOT_STARTED -> "You've started every book."
        // ALL never reaches here: a library with books always matches All.
        BookStatusFilter.FINISHED, BookStatusFilter.ALL -> "No finished books yet."
    }

/** A Library card's last line (spec §2.6): time left, "Finished · 12h 4m", or the length. */
internal fun cardLastLine(
    status: BookCardStatus?,
    durationMs: Long,
): String =
    when (status) {
        is BookCardStatus.InProgress -> "${DurationFormatter.hoursMinutes(status.timeLeftMs.milliseconds)} left"
        is BookCardStatus.Finished -> "Finished · ${DurationFormatter.hoursMinutes(status.durationMs.milliseconds)}"
        is BookCardStatus.NotStarted -> DurationFormatter.hoursMinutes(status.durationMs.milliseconds)
        null -> DurationFormatter.hoursMinutes(durationMs.milliseconds)
    }

/** The dense grid's hover/focus tooltip (spec §2.6 line 3): title · author · read by … · length. */
internal fun cardTooltip(book: BookListItem): String =
    buildList {
        add(book.title)
        book.authorNames.takeIf { it.isNotBlank() }?.let(::add)
        book.narratorNames.takeIf { it.isNotBlank() }?.let { add("read by $it") }
        add(DurationFormatter.hoursMinutes(book.duration.milliseconds))
    }.joinToString(" · ")

internal fun BookStatusFilter.webLabel(): String =
    when (this) {
        BookStatusFilter.ALL -> "All"
        BookStatusFilter.IN_PROGRESS -> "In progress"
        BookStatusFilter.NOT_STARTED -> "Not started"
        BookStatusFilter.FINISHED -> "Finished"
    }

/** "1,286 books · 41 days of listening" (spec §3.1.1 L4). Whole days, or whole hours under a day. */
internal fun libraryCountLine(
    all: Int,
    totalDurationMs: Long,
): String {
    val books = if (all == 1) "1 book" else "${groupedCount(all)} books"
    val days = totalDurationMs / MS_PER_DAY
    val span =
        if (days >= 1) {
            if (days == 1L) "1 day of listening" else "$days days of listening"
        } else {
            val hours = totalDurationMs / MS_PER_HOUR
            if (hours == 1L) "1 hour of listening" else "$hours hours of listening"
        }
    return "$books · $span"
}

/** Thousands separators, English only — the web's copy is English throughout. */
internal fun groupedCount(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * Keeps the status filter across Library visits in this tab (spec §3.1.1: session-scoped).
 *
 * The web resolves a FRESH [com.calypsan.listenup.client.presentation.library.LibraryViewModel] per
 * visit, so without this, opening a book and coming back would drop "Finished" back to All. Natives
 * keep their ViewModel across that round trip, so this only restores parity. A reload still resets it.
 */
internal fun rememberingStatusFilter(open: () -> LibrarySession): OpenLibrary {
    var remembered = BookStatusFilter.ALL
    return {
        val session = open()
        if (remembered != BookStatusFilter.ALL) session.onEvent(LibraryUiEvent.StatusFilterChanged(remembered))
        LibrarySession(
            state = session.state,
            onEvent = { event ->
                if (event is LibraryUiEvent.StatusFilterChanged) remembered = event.filter
                session.onEvent(event)
            },
            close = session.close,
        )
    }
}
