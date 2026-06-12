package com.calypsan.listenup.client.features.unmappedgenres

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.presentation.unmappedgenres.UnmappedGenresUiState
import com.calypsan.listenup.client.presentation.unmappedgenres.UnmappedGenresViewModel
import com.calypsan.listenup.core.GenreId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.genre_all_mapped
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import listenup.composeapp.generated.resources.genre_map_arrow
import listenup.composeapp.generated.resources.genre_map_raw_to
import listenup.composeapp.generated.resources.genre_no_live_genres
import listenup.composeapp.generated.resources.genre_nothing_to_map
import listenup.composeapp.generated.resources.genre_unmapped_title
import org.jetbrains.compose.resources.stringResource

/**
 * Unmapped-genre-strings curator screen.
 *
 * Shows the queue of raw genre strings the scanner couldn't resolve to a
 * canonical genre, aggregated by string with their book-count. Tapping a row
 * opens a genre picker; confirming binds the raw string to the picked genre
 * via [UnmappedGenresViewModel.mapToGenre], which adds an alias and converts
 * the pending entries into real `book_genres` rows on the server.
 *
 * The genre tree comes from the live Room observation; the unmapped queue
 * comes from a periodic RPC fetch (refreshed after every successful mapping).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnmappedGenresScreen(
    viewModel: UnmappedGenresViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pickerForRawString by remember { mutableStateOf<String?>(null) }

    val readyError = (state as? UnmappedGenresUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it.localizedString())
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            UnmappedGenresTopBar(
                state = state,
                onBackClick = onBackClick,
            )
        },
    ) { innerPadding ->
        UnmappedGenresContent(
            state = state,
            topPadding = innerPadding.calculateTopPadding(),
            onPickRawString = { pickerForRawString = it },
        )
    }

    pickerForRawString?.let { rawString ->
        val genres = (state as? UnmappedGenresUiState.Ready)?.genres ?: emptyList()
        GenrePickerDialog(
            rawString = rawString,
            genres = genres,
            onPick = { genreId ->
                viewModel.mapToGenre(rawString, genreId)
                pickerForRawString = null
            },
            onDismiss = { pickerForRawString = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnmappedGenresTopBar(
    state: UnmappedGenresUiState,
    onBackClick: () -> Unit,
) {
    Column {
        TopAppBar(
            title = { Text(stringResource(Res.string.genre_unmapped_title)) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
            },
        )
        val ready = state as? UnmappedGenresUiState.Ready
        if (ready != null && (ready.isLoadingUnmapped || ready.isSaving)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UnmappedGenresContent(
    state: UnmappedGenresUiState,
    topPadding: androidx.compose.ui.unit.Dp,
    onPickRawString: (String) -> Unit,
) {
    when (state) {
        is UnmappedGenresUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is UnmappedGenresUiState.Error -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = topPadding)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message.localized(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is UnmappedGenresUiState.Ready -> {
            if (state.unmapped.isEmpty()) {
                EmptyUnmappedMessage(topPadding = topPadding)
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = topPadding),
                ) {
                    items(state.unmapped, key = { it.rawString }) { summary ->
                        UnmappedRow(
                            summary = summary,
                            onClick = { onPickRawString(summary.rawString) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnmappedRow(
    summary: UnmappedStringSummary,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = summary.rawString, style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        if (summary.bookCount == 1) {
                            stringResource(Res.string.genre_book_count, summary.bookCount)
                        } else {
                            stringResource(Res.string.genre_books_count, summary.bookCount)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.genre_map_arrow),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GenrePickerDialog(
    rawString: String,
    genres: List<Genre>,
    onPick: (GenreId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.genre_map_raw_to, rawString)) },
        text = {
            if (genres.isEmpty()) {
                Text(stringResource(Res.string.genre_no_live_genres))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(genres, key = { it.id }) { genre ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(GenreId(genre.id)) }
                                    .padding(vertical = 12.dp),
                        ) {
                            Column {
                                Text(text = genre.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = genre.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

@Composable
private fun EmptyUnmappedMessage(topPadding: androidx.compose.ui.unit.Dp) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = topPadding)
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.genre_nothing_to_map),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.genre_all_mapped),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
