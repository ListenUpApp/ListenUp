package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.book_detail_readers_listening_now
import listenup.composeapp.generated.resources.common_see_all
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Section displaying readers of a book on the Book Detail screen.
 *
 * Shows a header with a [CountBadge] count and "See all" affordance, a sub-line with the
 * count of readers currently listening, and up to three [ReaderRow]s.
 *
 * All readers returned by [BookReadersUiState.Data] are currently listening — there is no
 * separate "finished" list on the model today. Consequently every row always shows the
 * [Icons.Default.GraphicEq] listening indicator and an active-ring avatar, and neither a
 * progress bar nor a "Finished {when}" line is rendered. When those fields are added to
 * [Reader], this composable should be updated accordingly.
 *
 * Note: no Surface/card wrapper is applied here. The wrapping card Surface is the
 * responsibility of the layout-assembly task so this section mirrors the frameless shape of
 * [ChaptersSection].
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

    val displayedReaders = allReaders.take(3)

    Column(modifier = modifier.fillMaxWidth()) {
        // Header row: title + CountBadge + spacer + "See all"
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.width(8.dp))

            CountBadge(count = allReaders.size)

            Spacer(modifier = Modifier.weight(1f))

            if (allReaders.size > 3) {
                TextButton(onClick = { /* TODO: Navigate to full readers list */ }) {
                    Text(
                        text = stringResource(Res.string.common_see_all),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Sub-line: "N listening now"
        // note: the design shows "N friends listening now" but the Reader model carries no
        // social-graph/friendship information — we degrade to a plain count sub-line.
        Text(
            text = stringResource(Res.string.book_detail_readers_listening_now, allReaders.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        displayedReaders.forEach { reader ->
            ReaderRow(
                reader = reader,
                onUserClick = onUserClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A single reader row: active-ring avatar, name, and a [Icons.Default.GraphicEq] indicator.
 *
 * All readers in [BookReadersUiState.Data.readers.currentlyListening] are currently
 * listening, so the ring and the listening icon are unconditional.
 *
 * note: the design also shows a progress bar + percentage when reading, and "Finished {when}"
 * when done. Neither field exists on [Reader] today — progress tracking and completion lists
 * are not yet exposed by the BookReadersRepository. Render only what the model supports.
 *
 * @param reader The reader to display.
 * @param onUserClick Callback when the row is clicked.
 * @param modifier Optional modifier.
 */
@Composable
private fun ReaderRow(
    reader: Reader,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clickable { onUserClick(reader.userId) }
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Active ring around avatar: all readers in this list are currently listening.
        UserAvatar(
            userId = reader.userId,
            size = AvatarSize.Medium,
            modifier =
                Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(13.dp))

        Text(
            text = reader.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // GraphicEq indicator: all readers are actively listening.
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}
