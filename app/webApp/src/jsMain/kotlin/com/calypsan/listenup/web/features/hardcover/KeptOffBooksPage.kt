package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** en.json's `hardcover.kept_off_title`. */
private const val TITLE = "Kept off Hardcover"
private const val COVER = 48

/** The breadcrumb's Hardcover, by position: Settings, Account, Hardcover, this page. */
private const val HARDCOVER_CRUMB = 2

/**
 * Settings → Account → Hardcover → Kept off Hardcover (#1541): the books kept off Hardcover, by title,
 * each with Sync again. Pure in [state]; the route says each Sync again in a toast and leaves after the
 * last.
 *
 * @param onSyncAgain Syncs one book with Hardcover again.
 * @param onOpenSettings The breadcrumb's Settings and Account.
 * @param onOpenHardcover The breadcrumb's Hardcover.
 */
@Composable
fun KeptOffBooksPage(
    state: KeptOffBooksUiState,
    onSyncAgain: (bookId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHardcover: () -> Unit,
) {
    Div(attrs = { classes("hc") }) {
        Breadcrumb(
            trail = listOf("Settings", "Account", "Hardcover", TITLE),
            onNavigate = { index -> if (index == HARDCOVER_CRUMB) onOpenHardcover() else onOpenSettings() },
        )
        PageHeader(title = TITLE)
        // en.json's `hardcover.kept_off_intro`.
        P(attrs = { classes("hc-lede", "hc-kept-intro") }) {
            Text("Nothing about these books is shared with Hardcover or brought in from it.")
        }
        when (state) {
            KeptOffBooksUiState.Loading -> {
                LoadingState(label = "Loading the books kept off Hardcover…")
            }

            // en.json's `hardcover.kept_off_unavailable`.
            KeptOffBooksUiState.Unavailable -> {
                EmptyState(title = "Couldn't load these books. Try again in a moment.", look = EmptyLook.Inline)
            }

            is KeptOffBooksUiState.Loaded -> {
                KeptOffList(books = state.books, onSyncAgain = onSyncAgain)
            }
        }
    }
}

/** The "Books" panel: its count, then a row per book with Sync again. */
@Composable
private fun KeptOffList(
    books: List<KeptOffBook>,
    onSyncAgain: (bookId: String) -> Unit,
) {
    Div(attrs = { classes("hc-kept-list") }) {
        // en.json's `hardcover.kept_off_books`.
        Panel(
            title = "Books",
            flush = true,
            trailing = { Span(attrs = { classes("hc-count", "mono") }) { Text(books.size.toString()) } },
            spokenCount = if (books.size == 1) "1 book" else "${books.size} books",
        ) {
            Ul(attrs = { classes("hc-match-list") }) {
                books.forEach { book ->
                    Li(attrs = { classes("hc-match-row") }) {
                        Cover(
                            title = book.title,
                            imageUrl = coverUrl(book.bookId, book.coverHash, width = COVER * 2),
                            size = COVER,
                            decorative = true,
                        )
                        Div(attrs = { classes("hc-match-text") }) {
                            Span(attrs = { classes("hc-match-title") }) { Text(book.title) }
                            if (book.authorNames.isNotBlank()) {
                                Span(attrs = { classes("hc-match-by") }) { Text(book.authorNames) }
                            }
                        }
                        // en.json's `hardcover.sync_again`: every row's button reads the same, so each
                        // is named for its own book — after the visible words, so a voice command of
                        // "click Sync again" still finds it (WCAG 2.5.3).
                        Button(
                            kind = ButtonKind.Secondary,
                            onClick = { onSyncAgain(book.bookId) },
                        ) {
                            Text("Sync again")
                            Span(attrs = { classes("sr-only") }) { Text(": ${book.title}") }
                        }
                    }
                }
            }
        }
    }
}
