package com.calypsan.listenup.web.features.contributordetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Panel
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Contributor Books — every book one person worked on in one role, over the shared
 * [com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel]'s state.
 *
 * The destination behind Contributor Detail's "View all". That page shows a preview of ten books
 * per role and a count badge saying how many there really are; until this page existed, a reader
 * whose author had forty books could read the number forty and reach exactly ten of them. The
 * badge was telling the truth about a total the browser had no way to open.
 *
 * Ordering is the ViewModel's, not this page's: series alphabetically with their books in sequence
 * order, then standalone books by title. Rendering them in any other order here would be a second
 * product rule competing with the one the natives already follow.
 *
 * Pure in [state], like [ContributorDetailPage] — the session wiring lives one level up.
 */
@Composable
fun ContributorBooksPage(
    state: ContributorBooksUiState,
    onOpenLibrary: () -> Unit,
    onOpenContributors: () -> Unit,
    onOpenContributor: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    Div(attrs = { classes("cb") }) {
        // Four levels, and the third goes back to the person — this page is a drill-down from one
        // contributor, not a sibling of the Contributors list.
        Breadcrumb(
            trail = listOf("Library", "Contributors", nameCrumb(state), roleCrumb(state)),
            onNavigate = { index ->
                when (index) {
                    0 -> onOpenLibrary()
                    1 -> onOpenContributors()
                    else -> onOpenContributor()
                }
            },
        )

        when (state) {
            is ContributorBooksUiState.Ready -> {
                ReadyBooks(state, onOpenBook)
            }

            is ContributorBooksUiState.Error -> {
                BooksWayBack(
                    heading = "These books can't be shown",
                    body = state.message,
                    onOpenContributor = onOpenContributor,
                )
            }

            ContributorBooksUiState.Loading, ContributorBooksUiState.Idle -> {
                Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            }
        }
    }
}

private fun nameCrumb(state: ContributorBooksUiState): String =
    if (state is ContributorBooksUiState.Ready) state.contributorName else "Contributor"

private fun roleCrumb(state: ContributorBooksUiState): String =
    if (state is ContributorBooksUiState.Ready) state.roleDisplayName else "Books"

/**
 * The shape every non-Ready state takes: what happened, and the one honest destination left —
 * the contributor this page drilled down from, not the list two levels up.
 */
@Composable
private fun BooksWayBack(
    heading: String,
    body: String,
    onOpenContributor: () -> Unit,
) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(heading) }
        P { Text(body) }
        Button(attrs = {
            classes("btn-c")
            attr("type", "button")
            onClick { onOpenContributor() }
        }) {
            Text("Back to contributor")
        }
    }
}

@Composable
private fun ReadyBooks(
    state: ContributorBooksUiState.Ready,
    onOpenBook: (String) -> Unit,
) {
    Div(attrs = { classes("cb-head") }) {
        H1(attrs = { classes("cb-role") }) { Text(state.roleDisplayName) }
        Div(attrs = { classes("cb-by") }) { Text(byLine(state.totalBooks, state.contributorName)) }
        // Same line the detail page's hero carries, from the same map: a role list is exactly where
        // a reader meets the pen name, because the alias is per-book and this is the whole role.
        creditedAsLine(state.bookCreditedAs)?.let { line ->
            Div(attrs = { classes("cb-alias") }) { Text(line) }
        }
    }

    // A Ready state with nothing in it is reachable: the role's last book can be re-credited while
    // the page is open. Saying so beats a page that is simply blank below its own heading.
    if (state.totalBooks == 0) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("No books in this role") }
            P {
                Text(
                    "${state.contributorName} is no longer credited as ${state.roleDisplayName.lowercase()} on any book.",
                )
            }
        }
        return
    }

    state.seriesGroups.forEach { group ->
        Div(attrs = { classes("cb-series") }) {
            Panel(title = group.seriesName, trailing = { BookCount(group.books.size) }) {
                BookGrid(group.books, state, onOpenBook)
            }
        }
    }

    if (state.hasStandaloneBooks) {
        Div(attrs = { classes("cb-standalone") }) {
            // Only titled when there is something to be "other" than — a page of nothing but
            // standalone books has no series to contrast with, so the heading would name a
            // distinction the reader cannot see.
            if (state.seriesGroups.isEmpty()) {
                BookGrid(state.standaloneBooks, state, onOpenBook)
            } else {
                Panel(title = "Other Books", trailing = { BookCount(state.standaloneBooks.size) }) {
                    BookGrid(state.standaloneBooks, state, onOpenBook)
                }
            }
        }
    }
}

@Composable
private fun BookGrid(
    books: List<BookListItem>,
    state: ContributorBooksUiState.Ready,
    onOpenBook: (String) -> Unit,
) {
    Div(attrs = { classes("cd-tile-grid") }) {
        books.forEach { book ->
            // The same tile Contributor Detail's role panels draw, deliberately: this page is that
            // page's preview continued, and a reader who has just clicked "View all" should land
            // on more of what they were looking at, not a second way of drawing a book.
            RoleTile(
                book = book,
                progress = state.bookProgress[book.id],
                onOpen = { onOpenBook(book.id.value) },
            )
        }
    }
}

@Composable
private fun BookCount(count: Int) {
    Span(attrs = { classes("cd-count-badge") }) { Text(count.toString()) }
}

/** "40 books by Stephen King" — singular at one, matching `contributor_books_by_count`. */
private fun byLine(
    total: Int,
    name: String,
): String = if (total == 1) "1 book by $name" else "$total books by $name"
