package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.common_see_all

/**
 * Section displaying readers of a book on the Book Detail screen.
 *
 * Shows:
 * - User's own reading history (if available)
 * - Other readers who are currently reading or have finished
 * - "See all" link if more than 3 other readers
 *
 * Does not render if there are no readers (including the user).
 *
 * @param bookId The book ID to load readers for
 * @param onUserClick Callback when a reader row is clicked (navigates to user profile)
 * @param modifier Optional modifier
 * @param viewModel The ViewModel for loading readers data
 */
@Composable
fun BookReadersSection(
    bookId: String,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookReadersViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.observeReaders(bookId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // Offline-first: show cached data immediately or hide section entirely.
    // Background refresh will populate Room, triggering a re-render when data arrives.
    // Loading and Error render nothing — preserve the "glass" behavior of the pre-sealed default.
    val ready = state as? BookReadersUiState.Ready ?: return
    val allReaders = ready.allReaders
    if (allReaders.isEmpty()) {
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header with "See all" link if > 3 readers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.book_detail_readers),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (allReaders.size > 3) {
                TextButton(onClick = { /* TODO: Navigate to full readers list */ }) {
                    Text(stringResource(Res.string.common_see_all))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Unified reader list (take 3) - includes current user with avatar
        val displayedReaders = allReaders.take(3)
        displayedReaders.forEach { reader ->
            ReaderRow(
                reader = reader,
                onUserClick = onUserClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
