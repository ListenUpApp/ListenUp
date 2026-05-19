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
import com.calypsan.listenup.client.features.library.AvatarOverlayData
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_what_others_are_listening_to

/**
 * Horizontal section showing books that other users are currently listening to.
 *
 * Displays each active session as a card with book cover and user avatar.
 * Data comes from Room database via SSE sync - no API calls.
 */
@Composable
fun CurrentlyListeningSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.currentlyListeningState.collectAsStateWithLifecycle()

    // Only render the Ready-with-data case; Loading, Error, and empty Ready render nothing.
    val ready = state as? CurrentlyListeningUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        // Section header
        Text(
            text = stringResource(Res.string.discover_what_others_are_listening_to),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of session cards
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = ready.sessions,
                key = { it.sessionId },
            ) { session ->
                BookCard(
                    bookId = session.bookId,
                    title = session.bookTitle,
                    coverPath = session.coverPath,
                    blurHash = session.coverBlurHash,
                    onClick = { onBookClick(session.bookId) },
                    authorName = session.authorName,
                    avatarOverlay =
                        AvatarOverlayData(
                            userId = session.userId,
                            displayName = session.displayName,
                            avatarType = session.avatarType,
                            avatarValue = session.avatarValue,
                            avatarColor = session.avatarColor,
                        ),
                    cardWidth = 140.dp,
                )
            }
        }
    }
}
