package com.calypsan.listenup.client.features.home.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.features.library.BookCard
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_continue_listening

/**
 * Horizontal scrolling row of Continue Listening books.
 *
 * Displays a section header followed by a horizontally scrollable
 * list of BookCard components.
 *
 * @param books List of books the user is currently listening to
 * @param onBookClick Callback when a book card is clicked
 * @param modifier Optional modifier
 */
@Composable
fun ContinueListeningRow(
    books: List<ContinueListeningBook>,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Section header
        Text(
            text = stringResource(Res.string.home_continue_listening),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontally scrolling book cards
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = books,
                key = { it.bookId },
            ) { book ->
                BookCard(
                    bookId = book.bookId,
                    title = book.title,
                    coverPath = book.coverPath,
                    blurHash = book.coverBlurHash,
                    onClick = { onBookClick(book.bookId) },
                    authorName = book.authorNames,
                    progress = book.progress,
                    timeRemaining = book.timeRemainingFormatted,
                    cardWidth = 140.dp,
                )
            }
        }
    }
}
