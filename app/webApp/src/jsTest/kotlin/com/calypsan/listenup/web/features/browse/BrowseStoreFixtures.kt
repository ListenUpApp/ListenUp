package com.calypsan.listenup.web.features.browse

import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import kotlinx.coroutines.flow.MutableStateFlow

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
