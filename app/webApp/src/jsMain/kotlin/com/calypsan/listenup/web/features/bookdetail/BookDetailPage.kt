package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.Pill
import com.calypsan.listenup.web.design.ProgressLine
import com.calypsan.listenup.web.design.TabItem
import com.calypsan.listenup.web.design.Tabs
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail — the Workbench layout in the Paper voice, over the shared
 * [com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel]'s state.
 *
 * Pure in [state]: everything rendered here comes from the ViewModel, and nothing is fetched from
 * this layer. That keeps the URL and layout specs able to drive any state deterministically, and
 * it is why the store wiring lives one level up in `WebAppRoot`.
 *
 * The pane is URL state (`?tab=…`), reported through [onSelectTab] so the caller can `replace`
 * the history entry: Back leaves the page, not the pane.
 *
 * There is a Play control and there is deliberately NO Download one. `BookDetailUiState.Ready`
 * carries `canDownload`, and it turns true on the web the moment playback becomes available — but
 * a browser cannot finish a download (`NoDownloadsService.supportsDownloads` is false), so
 * rendering that affordance would put a button on the page whose only possible outcome is nothing
 * happening.
 *
 * [onPlay] has no default for the same reason `WebAppRoot`'s `openPlayback` has none: a defaulted
 * no-op here would render a real, enabled Play button — gated on the book's own `canPlay`, which
 * knows nothing about whether a handler was supplied — and compile clean.
 */
@Composable
fun BookDetailPage(
    state: BookDetailUiState,
    tab: String,
    onSelectTab: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onPlay: () -> Unit,
    selection: Set<Int> = emptySet(),
    onSelectionChange: (Set<Int>) -> Unit = {},
) {
    Div(attrs = { classes("bd") }) {
        // The breadcrumb renders in every state, including the ones with no book: a page that
        // cannot show what you asked for must still show the way out of it.
        Breadcrumb(listOf("Library", crumb(state)), onNavigate = { onOpenLibrary() })

        when (state) {
            is BookDetailUiState.Loading -> {
                EmptyState(WebIcon.Clock, "Loading", "Reading this book from your library.")
            }

            is BookDetailUiState.Error -> {
                val (heading, body) = explain(state.error)
                // A state that can't show what was asked for still owes the reader somewhere to
                // go. Library is the only honest destination: web sync is unwritten, so a "sync
                // this browser" button would be a control with nothing behind it.
                EmptyState(WebIcon.Book, heading, body) {
                    Button(attrs = {
                        classes("btn-c")
                        onClick { onOpenLibrary() }
                    }) {
                        Text("Back to Library")
                    }
                }
            }

            is BookDetailUiState.Ready -> {
                BookHeader(state, onPlay)

                Tabs(
                    items =
                        listOf(
                            TabItem("overview", "Overview"),
                            TabItem("chapters", "Chapters", count = state.chapters.size.toString()),
                            TabItem(
                                "files",
                                "Files",
                                count =
                                    state.book.audioFiles.size
                                        .toString(),
                            ),
                        ),
                    active = tab,
                    onSelect = onSelectTab,
                )

                when (tab) {
                    "chapters" -> {
                        ChaptersPane(
                            chapters = state.chapters.toWebChapters(),
                            selection = selection,
                            onSelectionChange = onSelectionChange,
                        )
                    }

                    "files" -> {
                        FilesPane(state)
                    }

                    else -> {
                        OverviewPane(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookHeader(
    state: BookDetailUiState.Ready,
    onPlay: () -> Unit,
) {
    Div(attrs = { classes("bd-head") }) {
        Cover(title = state.book.title, size = COVER_SIZE, radius = COVER_RADIUS)
        Div(attrs = { classes("bd-tblock") }) {
            H1(attrs = { classes("bd-t") }) { Text(state.book.title) }
            byline(state)?.let { line -> Div(attrs = { classes("bd-by") }) { Text(line) } }
            // Only a book actually in progress gets a progress line — a 0% bar on an unstarted
            // book is decoration that reads as data.
            state.progress?.let { fraction ->
                ProgressLine(
                    percent = (fraction * PERCENT).toInt(),
                    remaining = state.timeRemainingFormatted.orEmpty(),
                )
            }
            // `canPlay` is the ViewModel's word on whether this book has anything to play at all.
            // A Play button on a book with no audio is a promise the page cannot keep.
            if (state.canPlay) {
                Div(attrs = { classes("bd-actions") }) {
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", "button")
                        onClick { onPlay() }
                    }) {
                        Icon(WebIcon.Play, size = PLAY_ICON_SIZE)
                        Text(if (state.progress != null) "Resume" else "Play")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewPane(state: BookDetailUiState.Ready) {
    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            Panel(title = "About") {
                if (state.descriptionText.isNotBlank()) {
                    P(attrs = {
                        style {
                            property("margin", "0 0 14px")
                            property("font-size", "14.5px")
                            property("line-height", "1.6")
                            property("color", "var(--ink-2)")
                        }
                    }) {
                        Text(state.descriptionText)
                    }
                }
                if (state.genres.isNotEmpty()) {
                    Div(attrs = {
                        style {
                            property("display", "flex")
                            property("flex-wrap", "wrap")
                            property("gap", "8px")
                        }
                    }) {
                        state.genres.forEach { genre -> Pill(genre.name) }
                    }
                }
                if (state.descriptionText.isBlank() && state.genres.isEmpty()) {
                    PaneHint("No description has been written for this book.")
                }
            }
        }
        Div(attrs = { classes("bd-side") }) {
            Panel(title = "Details") {
                MetaList(details(state))
            }
        }
    }
}

/**
 * The Details panel, built only from fields the book actually carries. A row that would read
 * "Unknown" is a row that shouldn't be drawn.
 */
private fun details(state: BookDetailUiState.Ready): List<MetaEntry> =
    buildList {
        add(MetaEntry("Duration", state.book.formatDuration(), machine = true))
        if (state.chapters.isNotEmpty()) add(MetaEntry("Chapters", state.chapters.size.toString()))
        state.year?.let { add(MetaEntry("Published", it.toString(), machine = true)) }
        state.book.publisher?.let { add(MetaEntry("Publisher", it)) }
        state.book.language?.let { add(MetaEntry("Language", it)) }
        if (state.book.narratorNames.isNotBlank()) add(MetaEntry("Narrator", state.book.narratorNames))
    }

/** "Author · read by Narrator", dropping either half when the book doesn't name it. */
private fun byline(state: BookDetailUiState.Ready): String? {
    val authors = state.book.authorNames.takeIf { it.isNotBlank() }
    val narrators = state.narrators.takeIf { it.isNotBlank() }?.let { "read by $it" }
    return listOfNotNull(authors, narrators).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun crumb(state: BookDetailUiState): String = if (state is BookDetailUiState.Ready) state.book.title else "Book"

/**
 * What to say about a failed load, chosen from the typed error rather than from its text.
 *
 * Every subtype but one renders its own `message`, which is the rule. `BookError.NotFound` is the
 * exception, and deliberately: its message is "This book no longer exists.", which is true for a
 * synced client and false for this one. A web client has no sync at all until the auth arc lands,
 * so a book missing from the local store means an empty browser far more often than a deleted
 * book — and telling a reader their book is gone when it is sitting on their server is the kind
 * of confident lie this codebase exists to avoid. Revisit once web sync can tell the two apart.
 */
private fun explain(error: AppError): Pair<String, String> =
    if (error is BookError.NotFound) {
        "Not in this browser's library" to
            "This browser hasn't synced a library yet, so there's nothing to show here."
    } else {
        "This book can't be shown" to error.message
    }

/**
 * The shape every state with no book takes: a mark, what happened, and — when there is somewhere
 * honest to go — the way out. The `.empty` rule in the sheet has always carried an `.ico` slot;
 * drawing it is what turns a bare sentence into a page.
 */
@Composable
private fun EmptyState(
    icon: WebIcon,
    heading: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Div(attrs = { classes("empty") }) {
        Div(attrs = { classes("ico") }) { Icon(icon, size = ICON_SIZE) }
        H3 { Text(heading) }
        P { Text(body) }
        action?.let { it() }
    }
}

@Composable
internal fun PaneHint(text: String) {
    P(attrs = {
        style {
            property("margin", "0")
            property("font-size", "13.5px")
            property("color", "var(--ink-3)")
            property("font-weight", "500")
        }
    }) {
        Text(text)
    }
}

private const val PERCENT = 100

private const val COVER_SIZE = 180

private const val COVER_RADIUS = 16

private const val ICON_SIZE = 24

private const val PLAY_ICON_SIZE = 16
