package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.web.features.seriesedit.AddSubSeriesDialogs
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
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
 */
@Composable
fun SeriesDetailPage(
    state: SeriesDetailUiState,
    onOpenLibrary: () -> Unit,
    onOpenBook: (String) -> Unit,
    onPlayBook: (String) -> Unit = {},
    onEdit: () -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onToggleSection: (String) -> Unit = {},
    addSubSeries: AddSubSeriesUiState = AddSubSeriesUiState.Closed(),
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit = {},
) {
    Div(attrs = { classes("sd") }) {
        // Renders in every state, including the ones with no series: a page that cannot show what
        // you asked for must still show the way out of it. On a sub-series it is the series' path.
        SeriesPathCrumb(
            ancestors = (state as? SeriesDetailUiState.Ready)?.ancestors.orEmpty(),
            current = crumb(state),
            onOpenLibrary = onOpenLibrary,
            onOpenSeries = onOpenSeries,
        )

        when (state) {
            is SeriesDetailUiState.Ready -> {
                ReadyContent(
                    state,
                    SeriesPageActions(
                        onOpenBook,
                        onPlayBook,
                        onEdit,
                        onOpenSeries,
                        onToggleSection,
                        onAddSubSeriesEvent,
                    ),
                )
                AddSubSeriesDialogs(addSubSeries, onAddSubSeriesEvent)
            }

            is SeriesDetailUiState.Error -> {
                PageHeader(title = crumb(state))
                WayBack(
                    heading = "This series can't be shown",
                    body = state.message,
                    onOpenLibrary = onOpenLibrary,
                )
            }

            SeriesDetailUiState.Loading, SeriesDetailUiState.Idle -> {
                PageHeader(title = crumb(state), pending = true)
                LoadingState()
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
    EmptyState(title = heading, body = body) {
        Button(kind = ButtonKind.Primary, onClick = { onOpenLibrary() }) {
            Text("Back to Library")
        }
    }
}

/** What the reader can do on a loaded series page, gathered so the sections take one parameter. */
internal class SeriesPageActions(
    val onOpenBook: (String) -> Unit,
    val onPlayBook: (String) -> Unit,
    val onEdit: () -> Unit,
    val onOpenSeries: (String) -> Unit,
    val onToggleSection: (String) -> Unit,
    val onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit,
)

@Composable
private fun ReadyContent(
    state: SeriesDetailUiState.Ready,
    actions: SeriesPageActions,
) {
    Hero(state, actions.onPlayBook, actions.onEdit)

    val description = state.seriesDescription
    if (!description.isNullOrBlank()) {
        Panel(title = "About") {
            Div(attrs = { classes("sd-desc") }) { P { Text(description) } }
        }
    }

    if (state.childSeries.isNotEmpty()) SubSeriesPanel(state, actions)

    // en.json's series.books
    Panel(title = "Books", trailing = { CountBadge(state.books.size) }) {
        if (state.isGrouped) {
            GroupedBooks(state, actions)
        } else {
            // A flat page is one section, shown exactly as before the hierarchy: no heading.
            Div(attrs = { classes("sd-books") }) {
                state.books.forEach { book ->
                    key(book.id.value) {
                        SeriesBookRow(
                            book = book,
                            seriesId = state.seriesId,
                            progress = state.bookProgress[book.id],
                            isFinished = book.id in state.finishedBookIds,
                            onOpen = { actions.onOpenBook(book.id.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    state: SeriesDetailUiState.Ready,
    onPlayBook: (String) -> Unit,
    onEdit: () -> Unit,
) {
    Div(attrs = { classes("sd-head") }) {
        val first = state.books.firstOrNull()
        Cover(
            title = state.seriesName,
            imageUrl = first?.let { coverUrl(it.id.value, it.coverHash, COVER_RUNG) },
            size = COVER_SIZE,
            radius = COVER_RADIUS,
            decorative = true,
        )
        Div(attrs = { classes("sd-tblock") }) {
            PageHeader(title = state.seriesName, display = true)

            authorLine(state)?.let { line -> Div(attrs = { classes("sd-by") }) { Text(line) } }

            Div(attrs = { classes("sd-stats") }) {
                // A parent counts its sub-series too: "4 series · 23 books" (en.json's series.count_books).
                val books =
                    if (state.isGrouped) {
                        seriesAndBookCount(state.childSeries.size, state.books.size)
                    } else {
                        seriesBookCount(state.books.size)
                    }
                StatPill(WebIcon.Book, books)
                // "of audio" — never "listened", the same distinction the contributor hero draws:
                // this is the series' total duration, not a record of what anyone has heard.
                StatPill(WebIcon.Clock, "${state.formatTotalDuration()} of audio")
                // Only when there is something finished to report. "0 finished" on a series nobody
                // has started is a statistic the page invented about a reader who did nothing.
                if (state.finishedCount > 0) {
                    StatPill(WebIcon.Check, "${state.finishedCount} finished")
                }
            }

            ResumeAction(state, onPlayBook)
        }

        // Icon-only, so the accessible name is the attribute rather than the content — the same
        // shape Book Detail and Contributor Detail use, for the same reason: a hero has no room
        // for a verb.
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Lg,
            onClick = { onEdit() },
            label = "Edit series",
            attrs = {
                classes("sd-edit")
            },
        ) { Icon(WebIcon.Pencil) }
    }
}

/**
 * Where the reader picks the series back up. `resumeTarget` is the ViewModel's word on it — the
 * first in-progress book, else the first unstarted one, and null once the whole series is finished.
 * A finished series gets no button rather than one that restarts book one, which is a decision the
 * reader did not make.
 *
 * On a parent page "Continue" alone is ambiguous across four series, so the button names the book
 * (en.json's `series.continue_title`) and says where it sits underneath (`series.continue_where`).
 */
@Composable
private fun ResumeAction(
    state: SeriesDetailUiState.Ready,
    onPlayBook: (String) -> Unit,
) {
    val target = state.resumeTarget ?: return
    val verb = if (state.bookProgress.containsKey(target)) "Continue" else "Start"
    val named = state.resumeBook?.takeIf { state.isGrouped }
    Div(attrs = { classes("sd-actions") }) {
        Button(kind = ButtonKind.Primary, onClick = { onPlayBook(target.value) }) {
            Icon(WebIcon.Play, size = PLAY_ICON_SIZE)
            Text(if (named != null) "$verb ${named.title}" else verb)
        }
        if (named != null) {
            Span(attrs = { classes("sd-resume-where") }) {
                Text(named.sequence?.let { "${named.seriesName} · Book $it" } ?: named.seriesName)
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
internal fun CountBadge(count: Int) {
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
internal fun SeriesBookRow(
    book: BookListItem,
    seriesId: String,
    progress: Float?,
    isFinished: Boolean,
    onOpen: () -> Unit,
) {
    Button(attrs = {
        classes("sd-book")
        attr("type", BUTTON_VALUE)
        onClick { onOpen() }
    }) {
        // Absent, not "—": a series with no numbering at all should read as a list of books, not
        // as a column of placeholders.
        sequenceLabel(book, seriesId)?.let { label ->
            Span(attrs = { classes("sd-seq") }) { Text(label) }
        }

        Div(attrs = { classes("sd-book-frame") }) {
            // Decorative: the book's title is the row's own text beside it.
            Cover(
                title = book.title,
                imageUrl = coverUrl(book.id.value, book.coverHash, ROW_COVER_RUNG),
                size = ROW_COVER_SIZE,
                radius = 0,
                decorative = true,
                overlay = { RestrictedMarker(book.id.value, compact = true) },
            )
            // `bookProgress` carries in-progress books ONLY — the ViewModel moves anything at or
            // past its finished threshold into `finishedBookIds` instead — so an unstarted book
            // draws no bar rather than a zero-width one that reads as data.
            progress?.let { fraction ->
                ProgressBar(value = fraction, label = "Listening progress", look = ProgressLook.Overlay)
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

/** The hero is the largest cover this page shows, so it asks for its own rung. See `coverUrl`. */
private const val BUTTON_VALUE = "button"

private const val COVER_RUNG = 600

private const val COVER_SIZE = 180

private const val COVER_RADIUS = 18

private const val ROW_COVER_RUNG = 150

private const val PLAY_ICON_SIZE = 17

private const val STAT_ICON_SIZE = 17

private const val DONE_ICON_SIZE = 15

private const val ROW_COVER_SIZE = 56
