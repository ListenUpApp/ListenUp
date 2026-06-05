package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.features.library.BookCard
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_continue_listening

/**
 * Horizontal scrolling row of Continue Listening items.
 *
 * Renders [ContinueListeningItem.Ready] items as full [BookCard]s and
 * [ContinueListeningItem.Loading] items as skeleton placeholder cards that
 * match the real card's dimensions. Rendered in a [BrowseCarousel]; the list order is
 * stable, so a Loading item transitions to Ready in place — no flicker or re-enter animation.
 *
 * @param items List of [ContinueListeningItem] — Ready or Loading
 * @param onBookClick Callback when a ready book card is clicked
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContinueListeningRow(
    items: List<ContinueListeningItem>,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    playingBookId: String? = null,
) {
    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.home_continue_listening),
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontally scrolling book cards
        BrowseCarousel(items = items) { item ->
            when (item) {
                is ContinueListeningItem.Ready -> {
                    BookCard(
                        bookId = item.book.bookId,
                        title = item.book.title,
                        coverPath = item.book.coverPath,
                        blurHash = item.book.coverBlurHash,
                        onClick = { onBookClick(item.bookId) },
                        authorName = item.book.authorNames,
                        progress = item.book.progress,
                        timeRemaining = item.book.timeRemainingFormatted,
                        isPlaying = item.book.bookId == playingBookId,
                        cardWidth = 140.dp,
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
                .width(140.dp)
                .height(198.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ),
    )
}
