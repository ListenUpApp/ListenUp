package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.BookMarkdown
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
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
    onEdit: () -> Unit = {},
    selection: Set<Int> = emptySet(),
    onSelectionChange: (Set<Int>) -> Unit = {},
    bookId: String? = null,
) {
    Div(attrs = { classes("bd") }) {
        // The breadcrumb renders in every state, including the ones with no book: a page that
        // cannot show what you asked for must still show the way out of it.
        Breadcrumb(listOf("Library", crumb(state)), onNavigate = { onOpenLibrary() })

        SharedHeader(state = state, bookId = bookId, onPlay = onPlay, onEdit = onEdit)

        when (state) {
            is BookDetailUiState.Loading -> {
                // The header (and with it the cover) is rendered ABOVE this `when`, so nothing to
                // do here — see the note on [SharedHeader].
                Unit
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

/**
 * The book's header, rendered in every state from one call site.
 *
 * ⛔ **The single call site is the whole point, and it is load-bearing for the shared-element
 * flight.** The cover used to be rendered inside each `when (state)` branch, which meant Compose
 * destroyed and rebuilt its DOM node when Loading became Ready. A `view-transition-name` on a node
 * that is removed mid-transition has its animation **cancelled** — and with every animation gone,
 * the transition simply ends. Measured: `ready` fired at 55 ms with five animations correctly
 * configured at their full duration, and `finished` fired at 66 ms. The flight was being cut down
 * 11 ms in, by this page rendering its own content.
 *
 * Keeping one call site means Compose updates that node rather than replacing it, so the cover
 * survives the state change and the morph runs to completion.
 *
 * The cover falls back to the id from the URL while the book is still loading — a cover URL needs
 * nothing else — so the thing the reader tapped is on screen immediately, with the text filling in
 * around it.
 */
@Composable
private fun SharedHeader(
    state: BookDetailUiState,
    bookId: String?,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
) {
    // Error renders no header at all: a page that cannot show the book must not show a cover and
    // the word "Loading" above the reason it failed. `BookDetailPanesTest` and `BookDetailTest`
    // both pin that the failure states offer their explanation and a way back, nothing else.
    if (state is BookDetailUiState.Error) return
    val ready = state as? BookDetailUiState.Ready
    val id = ready?.book?.id?.value ?: bookId ?: return

    Div(attrs = { classes("bd-head") }) {
        Cover(
            title = ready?.book?.title.orEmpty(),
            imageUrl = coverUrl(id, ready?.book?.coverHash, COVER_RUNG),
            size = COVER_SIZE,
            radius = COVER_RADIUS,
            heroName = HERO_COVER,
            heroBookId = id,
        )
        Div(attrs = { classes("bd-tblock") }) {
            if (ready == null) {
                // Says it is loading rather than showing a silent skeleton — `BookDetailTest` pins
                // that, because a quiet empty header is indistinguishable from a book with no
                // metadata at all.
                Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            } else {
                H1(attrs = { classes("bd-t") }) { Text(ready.book.title) }
                byline(ready)?.let { line -> Div(attrs = { classes("bd-by") }) { Text(line) } }
                ready.progress?.let { fraction ->
                    ProgressLine(
                        percent = (fraction * PERCENT).toInt(),
                        remaining = ready.timeRemainingFormatted.orEmpty(),
                    )
                }
                // The row itself is not gated on `canPlay`: a book with no playable audio is
                // exactly the one whose metadata most needs correcting, so Edit has to survive
                // the absence of Play.
                Div(attrs = { classes("bd-actions") }) {
                    if (ready.canPlay) {
                        Button(attrs = {
                            classes("btn")
                            attr("type", "button")
                            onClick { onPlay() }
                        }) {
                            Icon(WebIcon.Play, size = PLAY_ICON_SIZE)
                            Text(if (ready.progress != null) "Resume" else "Play")
                        }
                    }
                    Button(attrs = {
                        classes("btn-o")
                        attr("type", "button")
                        onClick { onEdit() }
                    }) { Text("Edit") }
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
        // The detail hero is the largest cover the web client shows, so it asks for its own rung
        // rather than reusing the grid's — a 300px derivative upscaled to 180 CSS px looks soft on
        // a 2x display. `coverHash` rides along so a re-covered book is not served a year-stale
        // image from cache; see [coverUrl].
        Cover(
            title = state.book.title,
            imageUrl = coverUrl(state.book.id.value, state.book.coverHash, COVER_RUNG),
            size = COVER_SIZE,
            radius = COVER_RADIUS,
            heroName = HERO_COVER,
        )
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
                    // The description arrives Markdown-flavoured and is untrusted external
                    // metadata; [BookMarkdown] owns both halves of that.
                    Div(attrs = { classes("bd-desc") }) {
                        BookMarkdown(state.descriptionText)
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

/** Shared with the library grid's tapped tile, so the cover flies between the two. */
const val HERO_COVER = "book-cover"

private const val COVER_SIZE = 180

/** Twice [COVER_SIZE], so the hero stays sharp on a 2x display. */
private const val COVER_RUNG = 360

private const val COVER_RADIUS = 16

private const val ICON_SIZE = 24

private const val PLAY_ICON_SIZE = 16
