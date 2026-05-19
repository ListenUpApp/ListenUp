package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * Data comes from Room database via SSE sync - no API calls.
 */
@Composable
fun RecentlyAddedSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.recentlyAddedState.collectAsStateWithLifecycle()

    // Only render the Ready-with-data case; Loading, Error, and empty Ready render nothing.
    val ready = state as? RecentlyAddedUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        // Section header
        Text(
            text = stringResource(Res.string.discover_recently_added),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of book cards
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = ready.books,
                key = { it.id },
            ) { book ->
                BookCard(
                    bookId = book.id,
                    title = book.title,
                    coverPath = book.coverPath,
                    blurHash = book.coverBlurHash,
                    onClick = { onBookClick(book.id) },
                    authorName = book.authorName,
                    cardWidth = 140.dp,
                )
            }
        }
    }
}
