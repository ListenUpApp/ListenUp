package com.calypsan.listenup.web.features.browse

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.library.BookCard
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Every book carrying one tag or one mood.
 *
 * Pure in [state]; the store wiring lives one level up. The books come from Room — the junctions
 * are synced — so this page works offline; the count and the total runtime come from the server,
 * which is authoritative over the whole live set rather than over whatever happens to be mirrored
 * here. When that call fails the ViewModel falls back to summing what it has, so a stats hiccup
 * degrades to an approximation rather than emptying a page that still has books on it.
 */
@Composable
fun BrowseFacetPage(
    state: BrowseFacetUiState,
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("brw") }) {
        when (state) {
            BrowseFacetUiState.Loading -> {
                Breadcrumb(trail = listOf(LIBRARY_CRUMB, "…"), onNavigate = { onOpenLibrary() })
                Div(attrs = { classes("skel", "brw-skel") })
            }

            is BrowseFacetUiState.NotFound -> {
                Breadcrumb(trail = listOf(LIBRARY_CRUMB, kindLabel(state.kind)), onNavigate = { onOpenLibrary() })
                Missing(kindLabel(state.kind), onOpenLibrary)
            }

            is BrowseFacetUiState.Ready -> {
                Breadcrumb(trail = listOf(LIBRARY_CRUMB, state.facetName), onNavigate = { onOpenLibrary() })
                Hero(
                    icon = if (state.kind == FacetKind.Mood) WebIcon.Sparkles else WebIcon.Hash,
                    eyebrow = kindLabel(state.kind),
                    title = state.facetName,
                    stats = listOf(bookCountLabel(state.bookCount), hoursLabel(state.totalDurationMs)),
                )
                BookGrid(state.books, onOpenBook)
            }
        }
    }
}

/**
 * One genre, the genres under it, and the books in scope.
 *
 * ⛔ The sub-genre toggle changes what "in scope" means, so the count has to move with it. A page
 * that said "412 books" while showing the 88 filed directly under Fantasy would be describing a
 * set the reader is not looking at.
 */
@Composable
fun GenreDestinationPage(
    state: GenreDestinationUiState,
    onOpenBook: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onToggleSubGenres: () -> Unit,
) {
    Div(attrs = { classes("brw") }) {
        when (state) {
            GenreDestinationUiState.Loading -> {
                Breadcrumb(trail = listOf(LIBRARY_CRUMB, "…"), onNavigate = { onOpenLibrary() })
                Div(attrs = { classes("skel", "brw-skel") })
            }

            GenreDestinationUiState.NotFound -> {
                Breadcrumb(trail = listOf(LIBRARY_CRUMB, "Genre"), onNavigate = { onOpenLibrary() })
                Missing("Genre", onOpenLibrary)
            }

            is GenreDestinationUiState.Ready -> {
                // The ancestors are the trail. A genre three levels down is meaningless without
                // them — "Grimdark" alone does not say it lives under Fantasy.
                Breadcrumb(
                    trail = listOf(LIBRARY_CRUMB) + state.breadcrumb.map { it.name } + state.identity.name,
                    onNavigate = { index ->
                        if (index ==
                            0
                        ) {
                            onOpenLibrary()
                        } else {
                            state.breadcrumb.getOrNull(index - 1)?.let { crumb -> onOpenGenre(crumb.genreId.value) }
                        }
                    },
                )
                Hero(
                    icon = WebIcon.Layers,
                    eyebrow = "Genre",
                    title = state.identity.name,
                    stats = listOf(bookCountLabel(state.stats.bookCount), hoursLabel(state.stats.totalDurationMs)),
                    blurb = state.identity.blurb,
                    hue = state.identity.hue,
                )

                if (state.hasSubs) {
                    Div(attrs = { classes("brw-subs") }) {
                        Button(attrs = {
                            classes("pill")
                            if (state.includeSubGenres) classes("on")
                            attr("type", "button")
                            attr("aria-pressed", state.includeSubGenres.toString())
                            onClick { onToggleSubGenres() }
                        }) { Text("Include sub-genres") }
                        state.subGenres.forEach { sub ->
                            Button(attrs = {
                                classes("pill", "brw-sub")
                                attr("type", "button")
                                onClick { onOpenGenre(sub.genreId.value) }
                            }) {
                                Text(sub.name)
                                Span(attrs = { classes("brw-sub-n") }) { Text(sub.bookCount.toString()) }
                            }
                        }
                    }
                }

                BookGrid(state.books, onOpenBook)
            }
        }
    }
}

/**
 * The tile, the name, and the two facts about the shelf.
 *
 * [hue] is the accent [FacetIdentity] derives from the genre's own name — the same one Android and
 * iOS paint their destination with, so a genre looks like itself on every client. Its companion
 * `FacetIcon` is *not* honoured here: this icon set is deliberately partial (see [WebIcon]) and
 * carries none of the twenty-seven category glyphs, and picking the nearest one would be a
 * different picture per platform rather than a shared identity. One shape, the real colour.
 */
@Composable
private fun Hero(
    icon: WebIcon,
    eyebrow: String,
    title: String,
    stats: List<String>,
    blurb: String? = null,
    hue: String? = null,
) {
    Div(attrs = { classes("brw-head") }) {
        Div(attrs = {
            classes("brw-icon")
            if (hue != null) style { property("--brw-hue", hue) }
        }) { Icon(icon, size = HERO_ICON) }
        Div(attrs = { classes("brw-titles") }) {
            Span(attrs = { classes("brw-eyebrow") }) { Text(eyebrow) }
            H1(attrs = { classes("brw-t") }) { Text(title) }
            blurb?.takeIf { it.isNotBlank() }?.let { P(attrs = { classes("brw-blurb") }) { Text(it) } }
            Div(attrs = { classes("brw-stats") }) {
                stats.forEach { Span(attrs = { classes("brw-stat") }) { Text(it) } }
            }
        }
    }
}

/**
 * The books, or an honest empty.
 *
 * ⛔ A facet with no books is not a broken page. It happens the moment the last book carrying a tag
 * loses it, and a blank grid under a hero that claims a count is the version that reads as a bug.
 */
@Composable
private fun BookGrid(
    books: List<BookListItem>,
    onOpenBook: (String) -> Unit,
) {
    if (books.isEmpty()) {
        P(attrs = { classes("brw-none") }) { Text("No books here yet.") }
        return
    }
    Div(attrs = { classes("lib-grid") }) {
        books.forEach { book ->
            BookCard(book = book, progress = 0f, onOpen = { onOpenBook(book.id.value) })
        }
    }
}

/** What a page reached by a link to something that is no longer there says. */
@Composable
private fun Missing(
    what: String,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("brw-empty") }) {
        H1 { Text("This ${what.lowercase()} is gone") }
        P { Text("It was removed, or the link is older than your library.") }
        Button(attrs = {
            classes("btn-c")
            attr("type", "button")
            onClick { onOpenLibrary() }
        }) { Text("Back to Library") }
    }
}

internal fun kindLabel(kind: FacetKind): String = if (kind == FacetKind.Mood) "Mood" else "Tag"

internal fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

/**
 * A total runtime, in the unit a reader thinks in.
 *
 * Under an hour is reported in minutes: "0h of audio" on a two-book tag is a number that reads as
 * an error rather than as a small collection.
 */
internal fun hoursLabel(totalMs: Long): String {
    val minutes = totalMs / MS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    return if (hours == 0L) "${minutes}m of audio" else "${hours}h of audio"
}

/** The trail always starts where the reader can get back to everything. */
private const val LIBRARY_CRUMB = "Library"

private const val MS_PER_MINUTE = 60_000L

private const val MINUTES_PER_HOUR = 60L

private const val HERO_ICON = 26
