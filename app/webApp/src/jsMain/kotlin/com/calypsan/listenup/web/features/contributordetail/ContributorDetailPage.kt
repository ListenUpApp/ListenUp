package com.calypsan.listenup.web.features.contributordetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.avatarTintFor
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.initialsFor
import com.calypsan.listenup.web.design.tintGradient
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
 * Contributor Detail — the person behind the books, over the shared
 * [com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel]'s state.
 *
 * Pure in [state]: everything rendered here comes from the ViewModel, nothing is fetched from this
 * layer — the same split [com.calypsan.listenup.web.features.bookdetail.BookDetailPage] makes, for
 * the same reason (the store wiring lives one level up, once B2 adds routing).
 *
 * No edit pencil and no delete affordance: there is no web contributor-edit form and no
 * destructive-action pattern on web yet, and a button that goes nowhere is the lie this arc keeps
 * refusing to ship. No "Show all" / "+N more" tile either — [RoleSection.showViewAll] and
 * [com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel] are the
 * hook for a per-role all-books screen when one exists; until then the panel's own count badge
 * already tells the truth about the total.
 */
@Composable
fun ContributorDetailPage(
    state: ContributorDetailUiState,
    onOpenLibrary: () -> Unit,
    onOpenContributors: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit = {},
) {
    Div(attrs = { classes("cd") }) {
        // The breadcrumb renders in every state, including the ones with no contributor: a page
        // that cannot show who you asked for must still show the way out of it.
        Breadcrumb(
            trail = listOf("Library", "Contributors", crumb(state)),
            onNavigate = { index -> if (index == 0) onOpenLibrary() else onOpenContributors() },
        )

        when (state) {
            is ContributorDetailUiState.Ready -> {
                ReadyContent(state, onOpenBook, onOpenSeries)
            }

            is ContributorDetailUiState.Error -> {
                WayBack(
                    heading = "This contributor can't be shown",
                    body = state.message,
                    onOpenContributors = onOpenContributors,
                )
            }

            ContributorDetailUiState.NotFound -> {
                // Terminal per the ViewModel's own contract — no retry can produce this
                // contributor, so the honest move is an explanation and a way back, not a spinner.
                WayBack(
                    heading = "This person isn't here any more",
                    body = "They may have been merged into another contributor, or the link is stale.",
                    onOpenContributors = onOpenContributors,
                )
            }

            ContributorDetailUiState.Loading, ContributorDetailUiState.Idle -> {
                Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            }
        }
    }
}

private fun crumb(state: ContributorDetailUiState): String =
    if (state is ContributorDetailUiState.Ready) state.contributor.name else "Contributor"

/**
 * The shape every non-Ready state takes: what happened, in plain words, and the one honest
 * destination left — Contributors, the list this page was reached from.
 */
@Composable
private fun WayBack(
    heading: String,
    body: String,
    onOpenContributors: () -> Unit,
) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(heading) }
        P { Text(body) }
        Button(attrs = {
            classes("btn-c")
            attr("type", "button")
            onClick { onOpenContributors() }
        }) {
            Text("Back to Contributors")
        }
    }
}

@Composable
private fun ReadyContent(
    state: ContributorDetailUiState.Ready,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    Hero(state)

    state.roleSections.forEach { section ->
        Div(attrs = { classes("cd-role-section") }) {
            Panel(title = section.displayName, trailing = { CountBadge(section.bookCount) }) {
                Div(attrs = { classes("cd-tile-grid") }) {
                    section.previewBooks.forEach { book ->
                        RoleTile(
                            book = book,
                            progress = state.bookProgress[book.id],
                            onOpen = { onOpenBook(book.id.value) },
                        )
                    }
                }
            }
        }
    }

    if (state.series.isNotEmpty()) {
        Div(attrs = { classes("cd-series-section") }) {
            Panel(title = "Series", trailing = { CountBadge(state.series.size) }) {
                Div(attrs = { classes("cd-series-grid") }) {
                    state.series.forEach { seriesWithBooks ->
                        SeriesCard(
                            seriesWithBooks = seriesWithBooks,
                            onOpen = { onOpenSeries(seriesWithBooks.series.id.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: ContributorDetailUiState.Ready) {
    Div(attrs = { classes("cd-hero") }) {
        Div(attrs = {
            classes("cd-avatar")
            // Decorative: the hero's accessible name is the H1 beside it, not the monogram.
            attr("aria-hidden", "true")
            style { property("background", avatarTintFor(state.contributor.name)) }
        }) { Text(initialsFor(state.contributor.name)) }

        Div(attrs = { classes("cd-name-block") }) {
            H1(attrs = { classes("cd-name") }) { Text(state.contributor.name) }

            Div(attrs = { classes("cd-roles") }) {
                state.roleSections.forEach { section ->
                    Span(attrs = {
                        classes("cd-role-chip")
                        if (section.role != ContributorRole.AUTHOR.apiValue) classes("is-muted")
                    }) { Text(heroChipLabel(section.role)) }
                }
                creditedAsLine(state.bookCreditedAs)?.let { line ->
                    Span(attrs = { classes("cd-alias") }) { Text(line) }
                }
            }

            Div(attrs = { classes("cd-stats") }) {
                StatPill(WebIcon.Book, bookCountLabel(state.bookCount))
                // "of audio" — never "listened": this is the library's total duration for the
                // contributor's books, not a record of how much of it anyone has actually heard.
                StatPill(WebIcon.Clock, "${state.formatTotalDuration()} of audio")
            }
        }
    }
}

@Composable
private fun StatPill(
    icon: WebIcon,
    label: String,
) {
    Span(attrs = { classes("cd-stat") }) {
        Icon(icon, size = STAT_ICON_SIZE)
        Span(attrs = { classes("cd-stat-label") }) { Text(label) }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Span(attrs = { classes("cd-count-badge") }) { Text(count.toString()) }
}

/**
 * One book tile in a role panel: cover (real artwork via [coverUrl], falling back to a
 * title-over-gradient tile the same way [com.calypsan.listenup.web.features.library.BookCard]
 * does for the library grid — [com.calypsan.listenup.web.design.Cover] itself is fixed-pixel and
 * can't take this grid's percentage sizing), the title, and a progress underline when
 * [progress] is known.
 */
@Composable
private fun RoleTile(
    book: BookListItem,
    progress: Float?,
    onOpen: () -> Unit,
) {
    var coverFailed by remember(book.id) { mutableStateOf(false) }

    Div(attrs = {
        classes("cd-tile")
        tabIndex(0)
        attr("role", "button")
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onOpen()
            }
        }
        onClick { onOpen() }
    }) {
        Div(attrs = { classes("cd-tile-frame") }) {
            if (coverFailed) {
                Div(attrs = { classes("cd-tile-fallback") }) { Text(book.title) }
            } else {
                Img(
                    src = coverUrl(book.id.value, book.coverHash, TILE_COVER_RUNG),
                    attrs = {
                        classes("cd-tile-cover")
                        alt(book.title)
                        attr("loading", "lazy")
                        attr("decoding", "async")
                        addEventListener("error") { coverFailed = true }
                    },
                )
            }
            // Absent, not zero-width: a book [progress] doesn't know about (never started, or
            // finished — `calculateProgressMap` excludes both) draws no bar rather than a false one.
            progress?.let { fraction ->
                Div(attrs = {
                    classes("cd-tile-progress")
                    style { width((fraction.coerceIn(0f, 1f) * PERCENT).percent) }
                })
            }
        }
        Div(attrs = { classes("cd-tile-title") }) { Text(book.title) }
    }
}

/**
 * A series this contributor's books belong to.
 *
 * ⛔ No done/total progress bar, unlike the artboard. [SeriesWithBooks] carries no per-book
 * completion data — `ContributorDetailUiState.Ready.bookProgress` deliberately excludes finished
 * books (`calculateProgressMap`'s `excludeComplete`), so there is no way to tell "finished" from
 * "never started" from what this page has. `SeriesDetailViewModel` has its own `FINISHED_THRESHOLD`
 * concept, but it isn't surfaced here — inventing a second one for this card would be a product
 * rule this page made up, not one the codebase already agreed on. The page this card now opens
 * does show that progress, because the ViewModel behind THAT page computes it.
 *
 * The card is a `<button>`, not a div with a click handler: it goes somewhere, so it has to be
 * reachable by keyboard and announce itself as a control — the same contract `.shelf-book-open`
 * and `.bd-by-name` already carry.
 */
@Composable
private fun SeriesCard(
    seriesWithBooks: SeriesWithBooks,
    onOpen: () -> Unit,
) {
    Button(attrs = {
        classes("cd-series-card")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        SeriesFan(seriesWithBooks.booksSortedBySequence())
        Div(attrs = { classes("cd-series-info") }) {
            Div(attrs = { classes("cd-series-name") }) { Text(seriesWithBooks.series.name) }
            Div(attrs = { classes("cd-series-count") }) { Text(bookCountLabel(seriesWithBooks.books.size)) }
        }
    }
}

/** The fanned-deck accent: up to [FAN_DEPTH] covers, front book first, each one a step further back. */
@Composable
private fun SeriesFan(booksInOrder: List<BookListItem>) {
    val fanned = booksInOrder.take(FAN_DEPTH)
    if (fanned.isEmpty()) return

    val fanWidth = FAN_FRONT_SIZE + (fanned.size - 1) * FAN_LEFT_STEP
    Div(attrs = {
        classes("cd-series-fan")
        style { property("width", "${fanWidth}px") }
    }) {
        // Rendered back-to-front so the front tile — the one with the shadow and the title — paints
        // on top, the same stacking order the artboard's own fan uses.
        fanned.indices.reversed().forEach { index ->
            val book = fanned[index]
            val isFront = index == 0
            Div(attrs = {
                classes("cd-fan-tile")
                if (isFront) classes("is-front")
                style {
                    property("left", "${index * FAN_LEFT_STEP}px")
                    property("top", "${index * FAN_TOP_STEP}px")
                    property("width", "${FAN_FRONT_SIZE - index * FAN_SIZE_STEP}px")
                    property("height", "${FAN_FRONT_SIZE - index * FAN_SIZE_STEP}px")
                    property(
                        "background",
                        tintGradient(
                            seed = book.title,
                            angleDegrees = FAN_GRADIENT_ANGLE,
                            firstSaturation = FAN_FIRST_SATURATION,
                            firstLightness = FAN_FIRST_LIGHTNESS,
                            secondSaturation = FAN_SECOND_SATURATION,
                            secondLightness = FAN_SECOND_LIGHTNESS,
                        ),
                    )
                }
            }) {
                if (isFront) {
                    Span(attrs = { classes("cd-fan-title") }) { Text(book.title) }
                }
            }
        }
    }
}

/** "Author" / "Narrator" / … — [ContributorRole.apiValue] capitalized for the hero chip. */
private fun heroChipLabel(role: String): String = role.replaceFirstChar { it.uppercase() }

/** "1 book" vs "64 books" — a one-book credit is real, so the plural is never assumed. */
private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

/**
 * "also credited as X" from the distinct aliases in [bookCreditedAs] — null when there are none,
 * so the hero never renders an empty "also credited as" line.
 */
private fun creditedAsLine(bookCreditedAs: Map<String, String>): String? {
    val aliases = bookCreditedAs.values.distinct()
    return if (aliases.isEmpty()) null else "also credited as ${aliases.joinToString(", ")}"
}

private const val STAT_ICON_SIZE = 17

private const val PERCENT = 100

/** The tile grid's covers are small; the smallest server rung comfortably covers a 6-column cell. */
private const val TILE_COVER_RUNG = 200

private const val FAN_DEPTH = 3

private const val FAN_FRONT_SIZE = 82

private const val FAN_SIZE_STEP = 5

private const val FAN_LEFT_STEP = 18

private const val FAN_TOP_STEP = 2.5

private const val FAN_GRADIENT_ANGLE = 165

private const val FAN_FIRST_SATURATION = 28

private const val FAN_FIRST_LIGHTNESS = 34

private const val FAN_SECOND_SATURATION = 32

private const val FAN_SECOND_LIGHTNESS = 14
