package com.calypsan.listenup.web.features.browse

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
import com.calypsan.listenup.core.GenreId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open browse-by-facet session — every book carrying one tag or one mood. */
class BrowseFacetSession(
    val state: StateFlow<BrowseFacetUiState>,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenBrowseFacet = (kind: FacetKind, facetId: String) -> BrowseFacetSession

/**
 * The production source: the shared [BrowseFacetViewModel], pointed at one facet.
 *
 * `load` fires here, as every other session's does — the load is what the session IS.
 */
fun graphBrowseFacet(koin: Koin): OpenBrowseFacet =
    { kind, facetId ->
        val viewModel = koin.get<BrowseFacetViewModel>()
        val store = ViewModelStore().apply { put("$kind:$facetId", viewModel) }
        viewModel.load(kind, facetId)
        BrowseFacetSession(state = viewModel.state, close = store::clear)
    }

/**
 * A session over a state that never changes — the shape specs pass in place of the graph.
 *
 * [onOpen] is what the route asked for. `/tag/x` and `/mood/x` differ only in the [FacetKind] the
 * route passes, so a fixture that swallowed its arguments could not tell a spec whether the two
 * paths are actually wired to different things.
 */
fun fixedBrowseFacet(
    state: BrowseFacetUiState,
    onOpen: (FacetKind, String) -> Unit = { _, _ -> },
): OpenBrowseFacet =
    { kind, facetId ->
        onOpen(kind, facetId)
        BrowseFacetSession(state = MutableStateFlow(state), close = {})
    }

/** An open genre-destination session — one genre, its children, and the books under it. */
class GenreDestinationSession(
    val state: StateFlow<GenreDestinationUiState>,
    val onToggleSubGenres: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. */
typealias OpenGenreDestination = (genreId: String) -> GenreDestinationSession

/** The production source: the shared [GenreDestinationViewModel], pointed at one genre. */
fun graphGenreDestination(koin: Koin): OpenGenreDestination =
    { genreId ->
        val viewModel = koin.get<GenreDestinationViewModel>()
        val store = ViewModelStore().apply { put(genreId, viewModel) }
        viewModel.load(GenreId(genreId))
        GenreDestinationSession(
            state = viewModel.state,
            onToggleSubGenres = viewModel::toggleIncludeSubGenres,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedGenreDestination(
    state: GenreDestinationUiState,
    onToggleSubGenres: () -> Unit = {},
    onOpen: (String) -> Unit = {},
): OpenGenreDestination =
    { genreId ->
        onOpen(genreId)
        GenreDestinationSession(
            state = MutableStateFlow(state),
            onToggleSubGenres = onToggleSubGenres,
            close = {},
        )
    }
