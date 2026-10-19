package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val MY_SHELVES = "My shelves"

private const val NEW_SHELF = "New shelf"

private const val LOADING_SHELVES = "Loading shelves"

private const val SHELVES_INVITATION = "A shelf is your own list of audiobooks — a series, a mood, a plan for the winter."

private const val COULDNT_LOAD_SHELVES = "Couldn't load your shelves."

private const val TRY_AGAIN = "Try again"

private const val SKELETON_SHELVES = 2

/**
 * `/shelves` (spec §3.2 H24): every shelf the reader owns, as Home's cards, with New shelf in the header. Words mirror
 * `en.json`'s `home.my_shelves`, `home.new_shelf`, `home.shelves_invitation`, `home.couldnt_load_shelves` and
 * `common.try_again`.
 */
@Composable
internal fun MyShelvesPage(
    state: MyShelvesUiState,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
    onRetry: () -> Unit,
) {
    // One column with the page's own gap, so the header and the grid never touch (the Readers page's shape).
    Div(attrs = { classes("shelves-list") }) {
        PageHeader(
            title = MY_SHELVES,
            actions = { Button(kind = ButtonKind.Secondary, onClick = onCreateShelf) { Text(NEW_SHELF) } },
        )
        when (state) {
            is MyShelvesUiState.Loading -> {
                Div(attrs = {
                    classes("shelves-page")
                    attr("role", "status")
                }) {
                    Span(attrs = { classes("sr-only") }) { Text(LOADING_SHELVES) }
                    repeat(SKELETON_SHELVES) {
                        Div(attrs = {
                            classes("skel", "home-shelf-skel")
                            attr("aria-hidden", "true")
                        })
                    }
                }
            }

            is MyShelvesUiState.Error -> {
                EmptyState(title = COULDNT_LOAD_SHELVES) {
                    Button(kind = ButtonKind.Secondary, onClick = onRetry) { Text(TRY_AGAIN) }
                }
            }

            is MyShelvesUiState.Ready -> {
                if (state.shelves.isEmpty()) {
                    EmptyState(title = SHELVES_INVITATION, look = EmptyLook.Inline)
                } else {
                    Div(attrs = { classes("shelves-page") }) {
                        state.shelves.forEach { shelf -> key(shelf.idString) { ShelfCard(shelf, onOpenShelf) } }
                    }
                }
            }
        }
    }
}

/** The `/shelves` branch: the list's session lives as long as the page does. */
@Composable
internal fun MyShelvesRoute(
    router: Router,
    openMyShelves: OpenMyShelves,
) {
    val session = rememberSession { openMyShelves() }
    MyShelvesPage(
        state = session.state.collectAsState().value,
        onOpenShelf = { id -> router.navigate(Routes.shelf(id)) },
        onCreateShelf = { router.navigate(Routes.newShelf) },
        onRetry = session.onRetry,
    )
}
