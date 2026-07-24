package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.features.library.BookCard
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_continue_listening

/** Width of a Continue Listening cover card — larger than a grid card so the hero row reads big. */
private val ContinueCardWidth = 176.dp

/**
 * Horizontal scrolling row of Continue Listening items.
 *
 * Renders [ContinueListeningItem.Ready] items as full [BookCard]s and
 * [ContinueListeningItem.Loading] items as skeleton placeholder cards that
 * match the real card's dimensions. Rendered in a snap [BrowseCarousel] (an unmasked LazyRow) so the
 * cards' soft cover shadows aren't clipped at the item bounds; the list order is stable, so a Loading
 * item transitions to Ready in place — no flicker or re-enter animation.
 *
 * @param items List of [ContinueListeningItem] — Ready or Loading
 * @param onBookClick Callback when a ready book card is clicked
 * @param modifier Optional modifier
 * @param playingBookId The bookId of the currently playing book, if any
 * @param isInSelectionMode Whether multi-select mode is active
 * @param selectedBookIds Set of bookIds that are currently selected
 * @param onBookLongPress Callback when a book card is long-pressed (enters selection mode)
 */
@Composable
fun ContinueListeningRow(
    items: List<ContinueListeningItem>,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    playingBookId: String? = null,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.home_continue_listening),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )

        Spacer(modifier = Modifier.height(Spacing.titleGap))

        BrowseCarousel(
            items = items,
            itemWidth = ContinueCardWidth,
            itemSpacing = Spacing.itemGap,
            contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
            key = { it.bookId },
        ) { item ->
            when (item) {
                is ContinueListeningItem.Ready -> {
                    BookCard(
                        cover = item.book.toCoverModel(),
                        onClick = { onBookClick(item.bookId) },
                        progress = item.book.progress,
                        timeRemaining = item.book.timeRemainingFormatted,
                        isPlaying = item.book.bookId == playingBookId,
                        cardWidth = ContinueCardWidth,
                        isInSelectionMode = isInSelectionMode,
                        isSelected = item.bookId in selectedBookIds,
                        onLongPress = onBookLongPress?.let { cb -> { cb(item.bookId) } },
                    )
                }

                is ContinueListeningItem.Loading -> {
                    ContinueListeningSkeletonCard()
                }
            }
        }
    }
}

/**
 * Skeleton placeholder card matching the dimensions of a real Continue Listening card.
 *
 * Shown when a position row has arrived but the corresponding book has not yet
 * synced into Room. Visible for < 1 s in typical conditions; static surfaceVariant
 * background is sufficient (no shimmer animation needed for this window).
 */
@Composable
private fun ContinueListeningSkeletonCard(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .width(ContinueCardWidth)
                .height(ContinueCardWidth)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = ContentShapes.card,
                ),
    )
}
