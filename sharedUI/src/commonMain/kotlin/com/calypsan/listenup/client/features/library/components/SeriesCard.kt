package com.calypsan.listenup.client.features.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.FannedDeck
import com.calypsan.listenup.client.design.components.FannedDeckCover
import com.calypsan.listenup.client.domain.model.SeriesWithBooks

/**
 * Series card with the signature M3 Expressive fanned cover deck.
 *
 * The deck of square covers is the hero; below it sit the series name and a
 * "*N* books · *Author*" line. Press uses a subtle scale for tactile feedback.
 *
 * @param seriesWithBooks The series with its associated books
 * @param onClick Callback when the card is clicked
 * @param modifier Optional modifier
 */
@Composable
fun SeriesCard(
    seriesWithBooks: SeriesWithBooks,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val series = seriesWithBooks.series
    val bookCount = seriesWithBooks.books.size

    val orderedBooks = remember(seriesWithBooks) { seriesWithBooks.booksSortedBySequence() }
    val deckCovers =
        remember(orderedBooks) {
            orderedBooks.map { book ->
                FannedDeckCover(
                    bookId = book.id.value,
                    coverPath = book.coverPath,
                    title = book.title,
                    author = book.authors.firstOrNull()?.name,
                )
            }
        }
    val author =
        orderedBooks
            .firstOrNull()
            ?.authors
            ?.firstOrNull()
            ?.name

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "card_scale",
    )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(28.dp))
                .then(
                    if (isFocused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp))
                    } else {
                        Modifier
                    },
                ).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            FannedDeck(covers = deckCovers, size = 104.dp, peek = 24.dp, max = 5, animate = true)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = series.name,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text =
                    buildString {
                        append(bookCount)
                        append(if (bookCount == 1) " book" else " books")
                        if (author != null) {
                            append(" · ")
                            append(author)
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
