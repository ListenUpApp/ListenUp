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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.FannedDeck
import com.calypsan.listenup.client.design.components.FannedDeckCover
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.features.seriesdetail.components.bookCountLabel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_count_books
import org.jetbrains.compose.resources.stringResource

/**
 * Series card with the signature M3 Expressive fanned cover deck.
 *
 * The deck of square covers is the hero; below it sit the series name and a
 * "*N* books · *Author*" line, then how far through the series the listener is
 * ([SeriesProgressBadge]). Press uses a subtle scale for tactile feedback.
 *
 * A top-level series with sub-series of its own stands on a second card layer (the stack hint) and
 * counts both: "4 series · 23 books".
 *
 * @param seriesWithBooks The series with its associated books
 * @param progress How far through the series the listener is — shown beneath the meta line
 * @param onClick Callback when the card is clicked
 * @param modifier Optional modifier
 */
@Composable
fun SeriesCard(
    seriesWithBooks: SeriesWithBooks,
    progress: SeriesProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val series = seriesWithBooks.series
    val bookCount = seriesWithBooks.books.size

    val isParent = seriesWithBooks.subSeriesCount > 0
    // A parent's books are its whole subtree, already in series order; sequences are per series.
    val orderedBooks =
        remember(seriesWithBooks) {
            if (isParent) seriesWithBooks.books else seriesWithBooks.booksSortedBySequence()
        }
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
                }.clip(MaterialTheme.shapes.large)
                .then(
                    if (isFocused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                    } else {
                        Modifier
                    },
                ).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    haptics.press()
                    onClick()
                }.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isParent) ParentStackHint()
            FannedDeck(
                covers = deckCovers,
                size = 104.dp,
                peek = 24.dp,
                max = 5,
                animate = true,
            )
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
            val countLine =
                if (isParent) {
                    stringResource(Res.string.series_count_books, seriesWithBooks.subSeriesCount, bookCount)
                } else {
                    bookCountLabel(bookCount)
                }
            Text(
                text = if (author != null) "$countLine · $author" else countLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            SeriesProgressBadge(progress = progress)
        }
    }
}

/** Two offset layers behind a parent series' deck: this card holds series, not just books. */
@Composable
private fun BoxScope.ParentStackHint() {
    listOf(2, 1).forEach { layer ->
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(x = (STACK_STEP_DP * layer).dp, y = (-STACK_STEP_DP * layer).dp)
                    .size(width = 150.dp, height = 104.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (layer == 2) 0.6f else 1f),
                    ),
        )
    }
}

private const val STACK_STEP_DP = 6
