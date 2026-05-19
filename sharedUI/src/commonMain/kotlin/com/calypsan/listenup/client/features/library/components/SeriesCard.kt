package com.calypsan.listenup.client.features.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageStorage
import org.koin.compose.koinInject

/**
 * Floating series card with editorial design.
 *
 * Design philosophy: Cover stack is the hero. No container boxing.
 * Individual covers in the stack have their own shadows.
 * Press interaction uses scale animation for tactile feedback.
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
    imageStorage: ImageStorage = koinInject(),
) {
    val series = seriesWithBooks.series
    val books = seriesWithBooks.books
    val bookCount = books.size

    // Domain model already has bookSequences as a Map<String, String?>
    val sequenceByBookId = seriesWithBooks.bookSequences

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "card_scale",
    )

    // Extract book covers sorted by series sequence, with IDs for server fallback
    val bookCovers =
        books
            .sortedBy { sequenceByBookId[it.id.value]?.toFloatOrNull() ?: Float.MAX_VALUE }
            .map { book ->
                val coverPath =
                    if (imageStorage.exists(book.id)) {
                        imageStorage.getCoverPath(book.id)
                    } else {
                        null
                    }
                book.id.value to coverPath
            }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.then(
                    if (isFocused) {
                        Modifier
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp),
                            ).padding(8.dp)
                    } else {
                        Modifier
                    },
                ).focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        // Animated cover stack (individual covers have their own shadows)
        AnimatedCoverStack(
            bookCovers = bookCovers,
            coverHeight = 140.dp,
            cycleDurationMs = 3000L,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Metadata
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = series.name,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "$bookCount ${if (bookCount == 1) "book" else "books"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
