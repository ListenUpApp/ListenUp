package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/**
 * The `/shelf/{id}` branch — one shelf, its books, and the owner's controls.
 *
 * Walking from one shelf to another replaces the session rather than showing the previous shelf's
 * books under the new one's name.
 */
@Composable
private fun ShelfDetailRoute(
    router: Router,
    openShelfDetail: OpenShelfDetail,
    shelfId: String,
    onHeroBookIdChange: (String) -> Unit,
) {
    val session = rememberSession(shelfId) { openShelfDetail(shelfId) }

    // The ViewModel reports a refused mutation exactly once, into a channel. Held here so the page
    // can render it until dismissed — a message that vanished on the next recomposition would be
    // no better than the silence this replaced.
    var notice by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { session.messages.collect { notice = it } }

    ShelfDetailPage(
        state = session.state.collectAsState().value,
        notice = notice,
        onDismissNotice = { notice = null },
        onOpenBook = { id ->
            onHeroBookIdChange(id)
            router.navigate(Routes.Book.detail(id))
        },
        onRemoveBook = session.onRemoveBook,
        onReorder = session.onReorder,
        onEditShelf = { id -> router.navigate(Routes.shelfEdit(id)) },
        onOpenLibrary = { router.navigate(Routes.library) },
    )
}

/**
 * The `/shelf/new` and `/shelf/{id}/edit` branches — one form, two modes.
 *
 * ## Where "back" goes
 *
 * `NavigateBack` is the only thing the ViewModel says on success, and it says the same word for a
 * save and for a delete — so the destination is decided here, where the difference is known:
 *
 * - **Created** a shelf → Home, which is where My Shelves lives. The new shelf's id never reaches
 *   this client, so the shelf itself is not a destination we could offer.
 * - **Saved** an edit → back to the shelf, which is what you were looking at.
 * - **Deleted** → Home. Returning to the shelf would land on its own "could not be opened" error,
 *   which is a true statement and a terrible way to confirm that a deletion worked.
 *
 * [deleted] exists only to tell the last two apart. It is set when Delete is pressed rather than
 * derived from state, because by the time `NavigateBack` arrives the shelf is already gone.
 */
@Composable
private fun ShelfEditRoute(
    router: Router,
    openShelfEdit: OpenShelfEdit,
    shelfId: String?,
) {
    val session = rememberSession(shelfId) { openShelfEdit(shelfId) }
    var deleted by remember(shelfId) { mutableStateOf(false) }

    // Both the page's own departures: it already asked about a draft, or the work was saved/deleted.
    val goHome = { router.navigate(Routes.home, force = true) }

    LaunchedEffect(session) {
        session.navActions.collect {
            if (shelfId == null || deleted) {
                goHome()
            } else {
                router.navigate(Routes.shelf(shelfId), force = true)
            }
        }
    }

    ShelfEditPage(
        state = session.state.collectAsState().value,
        isEditing = shelfId != null,
        onSave = session.onSave,
        onDelete = {
            deleted = true
            session.onDelete()
        },
        onDismissError = session.onDismissError,
        onCancel = { if (shelfId == null) goHome() else router.navigate(Routes.shelf(shelfId), force = true) },
        hasChanges = session.hasChanges,
    )
}

/**
 * The three shelf screens, behind the shell's single shelf branch.
 *
 * Split out so [RouteContent] has one condition for shelves rather than three. The URL grammar is
 * [shelfRouteOf]'s job; this only maps a parsed route to a screen.
 */
@Composable
internal fun ShelfRouteContent(
    shelfRoute: ShelfRoute,
    router: Router,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    onHeroBookIdChange: (String) -> Unit,
) {
    when (shelfRoute) {
        is ShelfRoute.Create -> {
            ShelfEditRoute(router = router, openShelfEdit = openShelfEdit, shelfId = null)
        }

        is ShelfRoute.Edit -> {
            ShelfEditRoute(router = router, openShelfEdit = openShelfEdit, shelfId = shelfRoute.shelfId)
        }

        is ShelfRoute.Detail -> {
            ShelfDetailRoute(
                router = router,
                openShelfDetail = openShelfDetail,
                shelfId = shelfRoute.shelfId,
                onHeroBookIdChange = onHeroBookIdChange,
            )
        }
    }
}
