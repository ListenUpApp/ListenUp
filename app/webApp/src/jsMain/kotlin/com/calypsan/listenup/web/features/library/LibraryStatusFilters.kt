package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryLength
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.libraryLengthOf
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyState
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * "1,286 books · 41 days of listening" (spec §3.1.1 L4). Whole days; whole hours under a day; whole
 * minutes under an hour; and no span at all under a minute, rather than "0 hours of listening".
 */
internal fun libraryCountLine(
    all: Int,
    totalDurationMs: Long,
): String {
    val books = if (all == 1) "1 book" else "${groupedCount(all)} books"
    val span =
        when (val length = libraryLengthOf(totalDurationMs)) {
            LibraryLength.None -> null
            is LibraryLength.Days -> listening(length.count, "day")
            is LibraryLength.Hours -> listening(length.count, "hour")
            is LibraryLength.Minutes -> listening(length.count, "minute")
        }
    return if (span == null) books else "$books · $span"
}

private fun listening(
    count: Long,
    unit: String,
): String = if (count == 1L) "1 $unit of listening" else "$count ${unit}s of listening"

/** Thousands separators, English only — the web's copy is English throughout. */
internal fun groupedCount(n: Int): String =
    n
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

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
            onShareBook = session.onShareBook,
        )
    }
}
