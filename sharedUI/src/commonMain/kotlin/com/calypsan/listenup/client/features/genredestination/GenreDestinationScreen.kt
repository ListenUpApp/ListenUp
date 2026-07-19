package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
import com.calypsan.listenup.core.GenreId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_not_found
import listenup.composeapp.generated.resources.genre_destination_audiobook_count
import listenup.composeapp.generated.resources.genre_destination_audiobooks_count
import org.jetbrains.compose.resources.stringResource

/**
 * Genre destination page — the standalone landing screen for a single genre, reached by tapping a
 * genre chip on Book Detail (or a sub-genre/breadcrumb crumb on this very screen). Combines the
 * genre's curated identity (hue-tinted hero, badge, breadcrumb) with the subtree-scope toggle and
 * an adaptive grid of every book in scope.
 *
 * Layout mirrors [com.calypsan.listenup.client.features.browsefacet.FacetBooksScreen] — the flat
 * facet-browse analogue for tags/moods: a tinted hero as the grid's first (full-span) item, so the
 * whole page scrolls as one list rather than nesting a scrollable body inside a fixed header.
 */
@Composable
fun GenreDestinationScreen(
    genreId: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    viewModel: GenreDestinationViewModel,
) {
    LaunchedEffect(genreId) {
        viewModel.load(GenreId(genreId))
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (val current = state) {
            GenreDestinationUiState.Loading -> {
                ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                FloatingBackButton(onBackClick = onBackClick, modifier = Modifier.align(Alignment.TopStart))
            }

            GenreDestinationUiState.NotFound -> {
                Text(
                    text = stringResource(Res.string.common_not_found, "Genre"),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
                FloatingBackButton(onBackClick = onBackClick, modifier = Modifier.align(Alignment.TopStart))
            }

            is GenreDestinationUiState.Ready -> {
                GenreDestinationContent(
                    state = current,
                    onBackClick = onBackClick,
                    onBookClick = onBookClick,
                    onGenreClick = onGenreClick,
                    onToggleIncludeSubGenres = viewModel::toggleIncludeSubGenres,
                )
            }
        }
    }
}

@Composable
private fun GenreDestinationContent(
    state: GenreDestinationUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onToggleIncludeSubGenres: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Spacing.screenMargin,
                end = Spacing.screenMargin,
                bottom = 32.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GenreDestinationHero(
                identity = state.identity,
                breadcrumb = state.breadcrumb,
                stats = state.stats,
                onBackClick = onBackClick,
                onGenreClick = onGenreClick,
            )
        }

        if (state.hasSubs) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(bottom = 20.dp)) {
                    SubtreeToggleSection(
                        state = state,
                        onToggle = onToggleIncludeSubGenres,
                        onSubGenreClick = onGenreClick,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = audiobooksHeader(state.stats.bookCount),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        items(
            items = state.books,
            key = { it.id.value },
        ) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id.value) },
                duration = book.formatDuration(),
            )
        }
    }
}

@Composable
private fun audiobooksHeader(bookCount: Int): String =
    if (bookCount == 1) {
        stringResource(Res.string.genre_destination_audiobook_count, bookCount)
    } else {
        stringResource(Res.string.genre_destination_audiobooks_count, bookCount)
    }

/** Floating back button shown on the Loading / NotFound states (which have no hero of their own). */
@Composable
private fun FloatingBackButton(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBackClick,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(Res.string.common_back),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
