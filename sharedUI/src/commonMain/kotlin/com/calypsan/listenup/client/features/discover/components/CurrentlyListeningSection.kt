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
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.currentlyListeningState.collectAsStateWithLifecycle()

    // Only render the Ready-with-data case; Loading, Error, and empty Ready render nothing.
    val ready = state as? CurrentlyListeningUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_what_others_are_listening_to),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of session cards
        BrowseCarousel(items = ready.sessions) { session ->
            BookCard(
                cover = session.toCoverModel(),
                onClick = { onBookClick(session.bookId) },
                avatarOverlay = AvatarOverlayData(userId = session.userId),
                cardWidth = 140.dp,
                isInSelectionMode = isInSelectionMode,
                isSelected = session.bookId in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(session.bookId) } },
            )
        }
    }
}
