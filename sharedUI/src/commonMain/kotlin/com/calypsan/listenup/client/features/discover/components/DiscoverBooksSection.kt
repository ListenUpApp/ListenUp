package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_refresh
import listenup.composeapp.generated.resources.discover_book_1_of_series
import listenup.composeapp.generated.resources.discover_discover_something_new

/**
 * Horizontal section showing random books for discovery.
 *
 * Series-aware: only shows first book in series (or standalone books).
 * Excludes books the user has already started.
 * Features a refresh button to get a new random selection.
 */
@Composable
fun DiscoverBooksSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.discoverBooksState.collectAsStateWithLifecycle()

    // Only render the Ready-with-data case; Loading, Error, and empty Ready render nothing.
    val ready = state as? DiscoverBooksUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        // Section header with refresh action (canonical SectionTitle + trailing icon slot)
        SectionTitle(
            title = stringResource(Res.string.discover_discover_something_new),
            modifier = Modifier.padding(horizontal = 16.dp),
            trailing = {
                IconButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(Res.string.common_refresh),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of book cards
        BrowseCarousel(items = ready.books) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id) },
                subtitle =
                    book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                        stringResource(Res.string.discover_book_1_of_series, series)
                    },
                cardWidth = 140.dp,
                isInSelectionMode = isInSelectionMode,
                isSelected = book.id in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(book.id) } },
            )
        }
    }
}
