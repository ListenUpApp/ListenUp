package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember

/**
 * `/book/{id}/match` and its Compare editions view: one [BookMatchSession] for the book, held across
 * both, so going to Compare and back never searches again. Closed when the route leaves the book.
 */
@Suppress("LongParameterList")
@Composable
fun BookMatchRoute(
    bookId: String,
    graph: MatchDetailsGraph,
    viewerId: String?,
    view: MatchView,
    onOpenCompare: () -> Unit,
    onCloseCompare: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBook: () -> Unit,
    onApplied: () -> Unit,
) {
    val session = remember(bookId) { graph.openBookMatch(bookId) }
    DisposableEffect(session) { onDispose { session.close() } }
    BookMatchPage(
        session = session,
        bookId = bookId,
        viewerId = viewerId,
        view = view,
        onOpenCompare = onOpenCompare,
        onCloseCompare = onCloseCompare,
        onOpenLibrary = onOpenLibrary,
        onOpenBook = onOpenBook,
        onApplied = onApplied,
    )
}

/**
 * Book Detail's receipt for [bookId], open while the page is. Leaving the book dismisses a receipt
 * still showing: it was about this visit, and the next visit should not open on old news.
 */
@Composable
fun BookMatchReceipt(
    bookId: String,
    graph: MatchDetailsGraph,
) {
    val session = remember(bookId) { graph.openMatchReceipt(bookId) }
    DisposableEffect(session) {
        onDispose {
            session.dismiss()
            session.close()
        }
    }
    MatchReceiptRegion(state = session.state.collectAsState().value, onUndo = session.undo, onDismiss = session.dismiss)
}
