package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.common_see_all

/**
 * Section displaying readers of a book on the Book Detail screen.
 *
 * Shows:
 * - Users currently listening (live, from active_sessions via SSE)
 * - "See all" link if more than 3 readers
 *
 * Renders nothing if there are no active listeners and no completions (NoReaders state),
 * or while loading. Errors are silently swallowed — the section is non-critical.
 *
 * @param bookId The book ID to load readers for.
 * @param onUserClick Callback when a reader row is clicked (navigates to user profile).
 * @param modifier Optional modifier.
 * @param viewModel The ViewModel for loading readers data; scoped to [bookId].
 */
@Composable
fun BookReadersSection(
    bookId: String,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookReadersViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Loading and Error render nothing — the Readers section is non-critical.
    val data = state as? BookReadersUiState.Data ?: return

    val allReaders = data.readers.currentlyListening
    if (allReaders.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
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

        allReaders.take(3).forEach { reader ->
            ReaderCardRow(
                reader = reader,
                onUserClick = onUserClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * A single reader row using the canonical [UserAvatar] composable for avatar display.
 *
 * @param reader The reader to display.
 * @param onUserClick Callback when the row is clicked.
 * @param modifier Optional modifier.
 */
@Composable
private fun ReaderCardRow(
    reader: Reader,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clickable { onUserClick(reader.userId) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        UserAvatar(
            userId = reader.userId,
            size = AvatarSize.Small,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = reader.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
