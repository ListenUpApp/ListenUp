package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_recently_added

/**
 * Horizontal section showing recently added books.
 *
 * Displays books sorted by createdAt timestamp (newest first).
 * Data comes from Room database via the sync firehose - no API calls.
 */
@Composable
fun RecentlyAddedSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.recentlyAddedState.collectAsStateWithLifecycle()

    // Only render the Ready-with-data case; Loading, Error, and empty Ready render nothing.
    val ready = state as? RecentlyAddedUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_recently_added),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of book cards
        BrowseCarousel(items = ready.books) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id) },
                cardWidth = 140.dp,
                isInSelectionMode = isInSelectionMode,
                isSelected = book.id in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(book.id) } },
            )
        }
    }
}
