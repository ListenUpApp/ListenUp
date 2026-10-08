package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingNavigationRow
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.hardcover_kept_off_intro
import listenup.composeapp.generated.resources.hardcover_kept_off_row_detail
import listenup.composeapp.generated.resources.hardcover_kept_off_row_detail_one
import listenup.composeapp.generated.resources.hardcover_kept_off_title
import listenup.composeapp.generated.resources.hardcover_kept_off_unavailable
import listenup.composeapp.generated.resources.hardcover_sync_again
import listenup.composeapp.generated.resources.hardcover_sync_again_label
import listenup.composeapp.generated.resources.hardcover_syncing_again
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val KeptOffCoverWidth = 48.dp
private val KeptOffCoverHeight = 72.dp
private val SyncAgainMinHeight = 44.dp

/** Rows flow into columns from this width, so a tablet uses its width (sharedUI rule 11). */
private val KeptOffRowMinWidth = 360.dp

/**
 * Settings → Account → Hardcover → Kept off Hardcover (#1541): the books kept off Hardcover, each with Sync again.
 * A book that syncs again leaves the list at once; [onSyncedAgain] says so on the shell's snackbar — which outlives
 * this screen — and closes it when the last one goes. A refusal shows on this screen's own snackbar.
 */
@Composable
fun HardcoverKeptOffScreen(
    onNavigateBack: () -> Unit,
    onSyncedAgain: (message: String, close: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeptOffBooksViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is KeptOffBooksEvent.SyncingAgain -> {
                    onSyncedAgain(getString(Res.string.hardcover_syncing_again, event.title), event.wasLast)
                }

                is KeptOffBooksEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.error.localizedString())
                }
            }
        }
    }
    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.hardcover_kept_off_title),
                onBack = onNavigateBack,
            )
        },
    ) { padding ->
        HardcoverKeptOffContent(state = state, onSyncAgain = viewModel::syncAgain, modifier = Modifier.padding(padding))
    }
}

/** The list for [state], without its scaffold. */
@Composable
internal fun HardcoverKeptOffContent(
    state: KeptOffBooksUiState,
    onSyncAgain: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        KeptOffBooksUiState.Loading -> {
            FullScreenLoadingIndicator(modifier = modifier)
        }

        KeptOffBooksUiState.Unavailable -> {
            Box(modifier = modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.hardcover_kept_off_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is KeptOffBooksUiState.Loaded -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = KeptOffRowMinWidth),
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(Res.string.hardcover_kept_off_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.md),
                    )
                }
                items(state.books, key = { it.bookId }) { book ->
                    KeptOffBookRow(
                        book = book,
                        onSyncAgain = { onSyncAgain(book.bookId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeptOffBookRow(
    book: KeptOffBook,
    onSyncAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val label = stringResource(Res.string.hardcover_sync_again_label, book.title)
    SectionSegment(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = book.bookId,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = null,
                title = book.title,
                author = book.authorNames,
                modifier = Modifier.size(width = KeptOffCoverWidth, height = KeptOffCoverHeight),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                if (book.authorNames.isNotBlank()) {
                    Text(
                        book.authorNames,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                border = listenUpOutlinedBorder(),
                onClick = {
                    haptics.press()
                    onSyncAgain()
                },
                modifier = Modifier.heightIn(min = SyncAgainMinHeight).semantics { contentDescription = label },
            ) {
                Text(stringResource(Res.string.hardcover_sync_again), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * "Kept off Hardcover · N books": the quiet row in Sync while any book is kept off (#1541). It opens the list —
 * a navigation row, with no chevron: Material has none.
 */
@Composable
internal fun HardcoverKeptOffRow(
    books: Int,
    onOpen: () -> Unit,
) {
    SettingNavigationRow(
        title = stringResource(Res.string.hardcover_kept_off_title),
        onClick = onOpen,
        subtitle =
            if (books == 1) {
                stringResource(Res.string.hardcover_kept_off_row_detail_one)
            } else {
                stringResource(Res.string.hardcover_kept_off_row_detail, books)
            },
        icon = Icons.Outlined.LinkOff,
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
