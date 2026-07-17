package com.calypsan.listenup.client.features.browsegenre

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreUiState
import com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.genre_browse_title
import listenup.composeapp.generated.resources.genre_include_subtree
import listenup.composeapp.generated.resources.genre_no_books_in_genre
import listenup.composeapp.generated.resources.genre_no_genres_yet
import listenup.composeapp.generated.resources.library_bookcount
import org.jetbrains.compose.resources.stringResource

/**
 * Browse-by-Genre screen.
 *
 * Left column lists the live genre tree (indented by depth). Tapping a genre
 * loads its books on the right via [BrowseGenreViewModel.selectGenre]. The
 * `includeDescendants` toggle widens the per-genre book fetch to the genre's
 * subtree using the server's path-prefix match.
 *
 * Two-pane layout on the same screen — works for phone (stacked) and tablet
 * (side-by-side) via the parent layout. This composable doesn't make the
 * layout decision itself; the navigation host can adapt for window-size
 * class if needed.
 *
 * Per the Genres design, the book list here is a list of [BookId]s — the
 * caller (the screen's host or a follow-up shell) is responsible for hydrating
 * book aggregates from Room. This keeps the screen lean and avoids cross-VM
 * coupling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseGenreScreen(
    viewModel: BrowseGenreViewModel,
    onBackClick: () -> Unit,
    onBookClick: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    initialGenreId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pre-select the genre the caller arrived on (e.g. a tapped genre chip). Keyed on the id so a
    // fresh navigation re-selects; browseBooks loads by id via RPC without waiting on the tree.
    LaunchedEffect(initialGenreId) {
        initialGenreId?.let { viewModel.selectGenre(GenreId(it)) }
    }

    val readyError = (state as? BrowseGenreUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it.localizedString())
            viewModel.clearError()
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BrowseGenreTopBar(
                state = state,
                onBackClick = onBackClick,
                onToggleIncludeDescendants = { viewModel.toggleIncludeDescendants() },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is BrowseGenreUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is BrowseGenreUiState.Error -> {
                ErrorContent(s.message.localized(), innerPadding.calculateTopPadding())
            }

            is BrowseGenreUiState.Ready -> {
                BrowseGenreReadyContent(
                    state = s,
                    onSelectGenre = { viewModel.selectGenre(it) },
                    onBookClick = onBookClick,
                    topPadding = innerPadding.calculateTopPadding(),
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseGenreTopBar(
    state: BrowseGenreUiState,
    onBackClick: () -> Unit,
    onToggleIncludeDescendants: () -> Unit,
) {
    Column {
        TopAppBar(
            title = { Text(stringResource(Res.string.genre_browse_title)) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
            },
            actions = {
                val ready = state as? BrowseGenreUiState.Ready
                if (ready != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.genre_include_subtree),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Switch(
                            checked = ready.includeDescendants,
                            onCheckedChange = { onToggleIncludeDescendants() },
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                }
            },
        )
        if ((state as? BrowseGenreUiState.Ready)?.isFetchingBooks == true) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = topPadding).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BrowseGenreReadyContent(
    state: BrowseGenreUiState.Ready,
    onSelectGenre: (GenreId) -> Unit,
    onBookClick: (BookId) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = topPadding),
    ) {
        // Top half: genre list. Bottom half: books for the selected genre.
        // Phone-first stacked layout — host can adapt for larger window-size classes.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.genres.isEmpty()) {
                EmptyGenresMessage()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding),
                ) {
                    items(state.genres, key = { it.id }) { genre ->
                        GenreRow(
                            genre = genre,
                            isSelected = state.selectedGenreId?.value == genre.id,
                            onClick = { onSelectGenre(GenreId(genre.id)) },
                        )
                    }
                }
            }
        }

        if (state.selectedGenreId != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BookListForGenre(
                    books = state.books,
                    onBookClick = onBookClick,
                    bottomPadding = bottomPadding,
                )
            }
        }
    }
}

@Composable
private fun GenreRow(
    genre: Genre,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val depth = genre.path.trim('/').count { it == '/' }
    val background =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClick = onClick)
                .padding(start = (16 + depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = genre.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (genre.bookCount > 0) {
            Text(
                text = stringResource(Res.string.library_bookcount, genre.bookCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookListForGenre(
    books: List<BookId>,
    onBookClick: (BookId) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.genre_no_books_in_genre),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        items(books, key = { it.value }) { bookId ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onBookClick(bookId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(text = bookId.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyGenresMessage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Category,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.genre_no_genres_yet),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
