package com.calypsan.listenup.web.features.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.web.nav.BrowseTarget
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/** The `/tag`, `/mood` and `/genre` branch of [RouteContent]. */
@Composable
internal fun BrowseRouteContent(
    target: BrowseTarget,
    router: Router,
    openBrowseFacet: OpenBrowseFacet,
    openGenreDestination: OpenGenreDestination,
) {
    when (target) {
        is BrowseTarget.Facet -> {
            BrowseFacetRoute(
                router = router,
                openBrowseFacet = openBrowseFacet,
                kind = target.kind,
                facetId = target.id,
            )
        }

        is BrowseTarget.Genre -> {
            GenreDestinationRoute(
                router = router,
                openGenreDestination = openGenreDestination,
                genreId = target.id,
            )
        }
    }
}

/**
 * Opens a browse-by-facet session over one tag or mood, and collects it.
 *
 * `/tag/grimdark` → `/tag/found-family` is a move a reader makes from the chips on a book.
 */
@Composable
private fun BrowseFacetRoute(
    router: Router,
    openBrowseFacet: OpenBrowseFacet,
    kind: FacetKind,
    facetId: String,
) {
    val session = rememberSession(kind, facetId) { openBrowseFacet(kind, facetId) }

    BrowseFacetPage(
        state = session.state.collectAsState().value,
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onOpenLibrary = { router.navigate(Routes.library) },
    )
}

/** Opens a genre destination and collects it. This page's own sub-genre pills navigate to sibling genres. */
@Composable
private fun GenreDestinationRoute(
    router: Router,
    openGenreDestination: OpenGenreDestination,
    genreId: String,
) {
    val session = rememberSession(genreId) { openGenreDestination(genreId) }

    GenreDestinationPage(
        state = session.state.collectAsState().value,
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onOpenGenre = { id -> router.navigate(Routes.genre(id)) },
        onOpenLibrary = { router.navigate(Routes.library) },
        onToggleSubGenres = session.onToggleSubGenres,
    )
}
