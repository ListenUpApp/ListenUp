package com.calypsan.listenup.web.features.seriesdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Series Detail — a series in reading order, over the shared
 * [com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel]'s state.
 *
 * Pure in [state], the same split [com.calypsan.listenup.web.features.bookdetail.BookDetailPage]
 * and [com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage] make: the store
 * wiring lives one level up, so this page renders what the ViewModel gives it and fetches nothing.
 *
 * **The order is the page.** A series is a sequence, so the books render as ordered rows carrying
 * their position, not as a grid that reflows into a different "third book" at every width — the
 * same reasoning `.shelf-books` already applies to a shelf.
 *
 * ⛔ **The hero cover is the first book's, not [SeriesDetailUiState.Ready.coverPath].** That field
 * is a *server filesystem path*, and the ViewModel collapses three sources into it (a local file,
 * the series' own artwork, the first book's cover) with no way left to tell which one won — so a
 * browser, which needs a URL, cannot address it. `/api/v1/series/{id}/cover` would be addressable
 * but 404s for every series whose artwork was never set, which is most of them, and
 * [Cover] falls back to a gradient rather than to a second URL. The first book's cover is a real
 * URL that always resolves, and it is the same image the ViewModel's own last-resort branch picks.
 *
 * No edit pencil: there is no web series-edit form, and a button that goes nowhere is the lie this
 * arc keeps refusing to ship.
 */
@Composable
fun SeriesDetailPage(
    state: SeriesDetailUiState,
    onOpenLibrary: () -> Unit,
    onOpenBook: (String) -> Unit,
    onPlayBook: (String) -> Unit = {},
) {
    Div(attrs = { classes("sd") }) {
        // Renders in every state, including the ones with no series: a page that cannot show what
        // you asked for must still show the way out of it.
        Breadcrumb(trail = listOf("Library", crumb(state)), onNavigate = { onOpenLibrary() })

        when (state) {
            is SeriesDetailUiState.Ready -> {
                ReadyContent(state, onOpenBook, onPlayBook)
            }

            is SeriesDetailUiState.Error -> {
                WayBack(
                    heading = "This series can't be shown",
                    body = state.message,
                    onOpenLibrary = onOpenLibrary,
                )
            }

            SeriesDetailUiState.Loading, SeriesDetailUiState.Idle -> {
                Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            }
        }
    }
}

private fun crumb(state: SeriesDetailUiState): String =
    if (state is SeriesDetailUiState.Ready) state.seriesName else "Series"

/**
 * The shape every non-Ready state takes: what happened, in plain words, and the one honest
 * destination left. Library rather than a series list — web has no series index to go back to.
 */
@Composable
private fun WayBack(
    heading: String,
    body: String,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(heading) }
        P { Text(body) }
        Button(attrs = {
            classes("btn-c")
            attr("type", "button")
            onClick { onOpenLibrary() }
        }) {
            Text("Back to Library")
        }
    }
}

@Composable
private fun ReadyContent(
    state: SeriesDetailUiState.Ready,
    onOpenBook: (String) -> Unit,
    onPlayBook: (String) -> Unit,
) {
    Hero(state, onPlayBook)

    val description = state.seriesDescription
    if (!description.isNullOrBlank()) {
        Panel(title = "About") {
            Div(attrs = { classes("sd-desc") }) { P { Text(description) } }
        }
    }

    Panel(title = "Books", trailing = { CountBadge(state.books.size) }) {
        Div(attrs = { classes("sd-books") }) {
            state.books.forEach { book ->
                BookRow(
                    book = book,
                    seriesId = state.seriesId,
                    progress = state.bookProgress[book.id],
                    isFinished = book.id in state.finishedBookIds,
                    onOpen = { onOpenBook(book.id.value) },
                )
            }
        }
    }
}

@Composable
private fun Hero(
    state: SeriesDetailUiState.Ready,
    onPlayBook: (String) -> Unit,
) {
    Div(attrs = { classes("sd-head") }) {
        val first = state.books.firstOrNull()
        Cover(
            title = state.seriesName,
            imageUrl = first?.let { coverUrl(it.id.value, it.coverHash, COVER_RUNG) },
            size = COVER_SIZE,
            radius = COVER_RADIUS,
        )
        Div(attrs = { classes("sd-tblock") }) {
            H1(attrs = { classes("sd-t") }) { Text(state.seriesName) }

            authorLine(state)?.let { line -> Div(attrs = { classes("sd-by") }) { Text(line) } }

            Div(attrs = { classes("sd-stats") }) {
                StatPill(WebIcon.Book, bookCountLabel(state.books.size))
                // "of audio" — never "listened", the same distinction the contributor hero draws:
                // this is the series' total duration, not a record of what anyone has heard.
                StatPill(WebIcon.Clock, "${state.formatTotalDuration()} of audio")
                // Only when there is something finished to report. "0 finished" on a series nobody
                // has started is a statistic the page invented about a reader who did nothing.
                if (state.finishedCount > 0) {
                    StatPill(WebIcon.Check, "${state.finishedCount} finished")
                }
            }

            // `resumeTarget` is the ViewModel's word on where a reader picks the series back up —
            // the first in-progress book, else the first unstarted one, and null once the whole
            // series is finished. A finished series gets no button rather than one that restarts
            // book one, which is a decision the reader did not make.
            state.resumeTarget?.let { target ->
                Div(attrs = { classes("sd-actions") }) {
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", "button")
                        onClick { onPlayBook(target.value) }
                    }) {
                        Icon(WebIcon.Play, size = PLAY_ICON_SIZE)
                        Text(if (state.bookProgress.containsKey(target)) "Continue" else "Start")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    icon: WebIcon,
    label: String,
) {
    Span(attrs = { classes("sd-stat") }) {
        Icon(icon, size = STAT_ICON_SIZE)
        Span(attrs = { classes("sd-stat-label") }) { Text(label) }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Span(attrs = { classes("sd-count-badge") }) { Text(count.toString()) }
}

/**
 * One book in the series: its position, its cover, its title and author, and where the reader is
 * in it.
 *
 * The position comes from THIS series' membership — a book in two series has two positions, and
 * `first()` would show the wrong one on one of the two pages. [BookListItem.series] is searched by
 * [seriesId] rather than indexed by row, because the ViewModel sorts unnumbered books to the end
 * and their row number is not their sequence.
 */
@Composable
private fun BookRow(
    book: BookListItem,
    seriesId: String,
    progress: Float?,
    isFinished: Boolean,
    onOpen: () -> Unit,
) {
    var coverFailed by remember(book.id) { mutableStateOf(false) }

    Button(attrs = {
        classes("sd-book")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        // Absent, not "—": a series with no numbering at all should read as a list of books, not
        // as a column of placeholders.
        sequenceLabel(book, seriesId)?.let { label ->
            Span(attrs = { classes("sd-seq") }) { Text(label) }
        }

        Div(attrs = { classes("sd-book-frame") }) {
            if (coverFailed) {
                Div(attrs = { classes("sd-book-fallback") }) { Text(book.title) }
            } else {
                Img(
                    src = coverUrl(book.id.value, book.coverHash, ROW_COVER_RUNG),
                    attrs = {
                        classes("sd-book-cover")
                        alt(book.title)
                        attr("loading", "lazy")
                        attr("decoding", "async")
                        addEventListener("error") { coverFailed = true }
                    },
                )
            }
            // `bookProgress` carries in-progress books ONLY — the ViewModel moves anything at or
            // past its finished threshold into `finishedBookIds` instead — so an unstarted book
            // draws no bar rather than a zero-width one that reads as data.
            progress?.let { fraction ->
                Div(attrs = {
                    classes("sd-book-progress")
                    style { width((fraction.coerceIn(0f, 1f) * PERCENT).percent) }
                })
            }
        }

        Div(attrs = { classes("sd-book-text") }) {
            Div(attrs = { classes("sd-book-t") }) { Text(book.title) }
            Div(attrs = { classes("sd-book-sub") }) { Text(subtitleFor(book)) }
        }

        if (isFinished) {
            Span(attrs = { classes("sd-done") }) {
                Icon(WebIcon.Check, size = DONE_ICON_SIZE)
                Text("Finished")
            }
        }
    }
}

/** This book's position in [seriesId], as a person would write it — `"#1"`, `"#1.5"`, or null. */
private fun sequenceLabel(
    book: BookListItem,
    seriesId: String,
): String? =
    book.series
        .firstOrNull { it.seriesId == seriesId }
        ?.sequenceLabel
        ?.let { "#$it" }

/** "Brandon Sanderson · 45h 12m", dropping the author when the book names none. */
private fun subtitleFor(book: BookListItem): String {
    val authors = book.authors.joinToString(", ") { it.name }
    return if (authors.isBlank()) book.formatDuration() else "$authors · ${book.formatDuration()}"
}

/**
 * Every author across the series, as the ViewModel deduped them — a multi-author series (Wheel of
 * Time) or an anthology names all of them, not just whoever wrote book one. Null when no book in
 * the series names an author, so the hero renders no empty line.
 */
private fun authorLine(state: SeriesDetailUiState.Ready): String? {
    val names = state.seriesAuthors.map { it.name }
    return if (names.isEmpty()) null else names.joinToString(", ")
}

/** "1 book" vs "5 books" — a one-book series is real, so the plural is never assumed. */
private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

/** The hero is the largest cover this page shows, so it asks for its own rung. See `coverUrl`. */
private const val COVER_RUNG = 600

private const val COVER_SIZE = 180

private const val COVER_RADIUS = 18

private const val ROW_COVER_RUNG = 150

private const val PLAY_ICON_SIZE = 17

private const val STAT_ICON_SIZE = 17

private const val DONE_ICON_SIZE = 15

private const val PERCENT = 100
