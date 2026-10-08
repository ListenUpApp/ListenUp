package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState

/**
 * `/contributor/{id}/match`: one [PersonMatchSession] for the person, closed when the route leaves them.
 */
@Suppress("LongParameterList")
@Composable
fun PersonMatchRoute(
    contributorId: String,
    graph: MatchDetailsGraph,
    viewerId: String?,
    onOpenLibrary: () -> Unit,
    onOpenContributor: () -> Unit,
    onEditByHand: () -> Unit,
    onApplied: () -> Unit,
) {
    val session = remember(contributorId) { graph.openPersonMatch(contributorId) }
    DisposableEffect(session) { onDispose { session.close() } }
    PersonMatchPage(
        session = session,
        contributorId = contributorId,
        viewerId = viewerId,
        onOpenLibrary = onOpenLibrary,
        onOpenContributor = onOpenContributor,
        onEditByHand = onEditByHand,
        onApplied = onApplied,
    )
}

/**
 * The contributor page's receipt for [contributorId], open while the page is: "Changed photo and biography for
 * Ray Porter" with Undo, until dismissed. Leaving the person dismisses one still showing, as Book Detail's does.
 *
 * The sentence names the person, so nothing shows until [name] is known — the page reads it from this device a
 * moment after it opens, and the receipt then arrives and takes focus.
 */
@Composable
fun PersonMatchReceipt(
    contributorId: String,
    name: String?,
    graph: MatchDetailsGraph,
) {
    val session = remember(contributorId) { graph.openMatchReceipt(contributorId) }
    DisposableEffect(session) {
        onDispose {
            session.dismiss()
            session.close()
        }
    }
    val state = session.state.collectAsState().value
    MatchReceiptRegion(
        state = if (name == null) MatchReceiptUiState.None else state,
        onUndo = session.undo,
        onDismiss = session.dismiss,
        subject = ReceiptSubject.Person(name.orEmpty()),
    )
}
