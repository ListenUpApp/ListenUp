package com.calypsan.listenup.client.features.library.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calypsan.listenup.client.design.components.BookCoverImage
import kotlinx.coroutines.delay

/**
 * Displays book covers with layout based on count:
 * - 1 book: Full-width cropped cover
 * - 2 books: Side by side layout
 * - 3+ books: Animated stack with Material 3 Expressive spring animations
 *
 * @param bookCovers List of (bookId, coverPath) pairs for server URL fallback
 * @param modifier Optional modifier
 * @param coverHeight Height of the cover area
 * @param cycleDurationMs Duration of each animation cycle (3+ books only)
 */
@Composable
fun AnimatedCoverStack(
    bookCovers: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
    coverHeight: Dp = 120.dp,
    cycleDurationMs: Long = 3000L,
) {
    val coverCount = bookCovers.size

    when {
        coverCount == 0 -> {
            // Empty placeholder - full width
            FullWidthCover(
                bookId = "",
                coverPath = null,
                modifier = modifier.height(coverHeight),
            )
        }

        coverCount == 1 -> {
            // Single book - full width cropped
            FullWidthCover(
                bookId = bookCovers[0].first,
                coverPath = bookCovers[0].second,
                modifier = modifier.height(coverHeight),
            )
        }

        coverCount == 2 -> {
            // Two books - side by side
            TwoUpCoverLayout(
                bookCovers = bookCovers,
                modifier = modifier.height(coverHeight),
            )
        }

        else -> {
            // 3+ books - animated stack
            AnimatedStackLayout(
                bookCovers = bookCovers,
                coverHeight = coverHeight,
                cycleDurationMs = cycleDurationMs,
                modifier = modifier,
            )
        }
    }
}

/**
 * Full-width cover image, cropped to fill the container.
 */
@Composable
private fun FullWidthCover(
    bookId: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (bookId.isNotBlank()) {
            BookCoverImage(
                bookId = bookId,
                coverPath = coverPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/**
 * Two covers displayed side by side.
 * Front cover is larger (60% width), back cover is smaller (40% width).
 * Both maintain square aspect ratio but scale to fill available space.
 */
@Composable
private fun TwoUpCoverLayout(
    bookCovers: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Primary cover — takes more space
        StackedCover(
            bookId = bookCovers.getOrNull(0)?.first ?: "",
            coverPath = bookCovers.getOrNull(0)?.second,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        )
        // Secondary cover — equal size
        StackedCover(
            bookId = bookCovers.getOrNull(1)?.first ?: "",
            coverPath = bookCovers.getOrNull(1)?.second,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        )
    }
}

/**
 * Animated stack for 3+ covers with spring animations.
 * Front cover is prominently displayed on the LEFT, with other covers
 * fanned out behind it to the right.
 */
@Composable
private fun AnimatedStackLayout(
    bookCovers: List<Pair<String, String?>>,
    coverHeight: Dp,
    cycleDurationMs: Long,
    modifier: Modifier = Modifier,
) {
    val coverCount = bookCovers.size

    // Randomize starting position and add small stagger so cards don't animate in sync
    val startingIndex = remember { (0 until coverCount).random() }
    val staggerDelayMs = remember { (0L..500L).random() }
    var frontIndex by remember { mutableIntStateOf(startingIndex) }

    // Cycle through covers with staggered timing
    LaunchedEffect(coverCount) {
        delay(staggerDelayMs)
        while (true) {
            delay(cycleDurationMs)
            frontIndex = (frontIndex + 1) % coverCount
        }
    }

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(coverHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val coverHeightPx = with(density) { coverHeight.toPx() }
        val coverWidthPx = coverHeightPx // Square covers (1:1 aspect ratio)

        // Calculate overlap so stack fills the width, capped to maintain overlap for small counts
        val overlapOffsetPx =
            minOf(
                (containerWidthPx - coverWidthPx) / (coverCount - 1).coerceAtLeast(1),
                coverWidthPx * 0.35f,
            )

        bookCovers.forEachIndexed { index, (bookId, coverPath) ->
            // visualPosition: 0 = back, coverCount-1 = front (for z-ordering)
            val visualPosition =
                calculateVisualPosition(
                    index = index,
                    frontIndex = frontIndex,
                    totalCount = coverCount,
                )

            // X position: front cover on LEFT (offset 0), back covers fan out to RIGHT
            // Invert the position for x-offset calculation
            val depthPosition = coverCount - 1 - visualPosition // 0 = front, higher = further back
            val targetXOffsetPx = depthPosition * overlapOffsetPx

            val animatedXOffset by animateFloatAsState(
                targetValue = targetXOffsetPx,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                label = "xOffset",
            )

            // Front cover slightly larger (visualPosition high = front = larger)
            val targetScale = 0.92f + visualPosition.toFloat() / (coverCount - 1) * 0.08f
            val animatedScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                label = "scale",
            )

            // Z-index: front cover on top (highest z)
            val animatedZIndex by animateFloatAsState(
                targetValue = visualPosition.toFloat(),
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                label = "zIndex",
            )

            StackedCover(
                bookId = bookId,
                coverPath = coverPath,
                modifier =
                    Modifier
                        .height(coverHeight)
                        .aspectRatio(1f) // Square covers
                        .zIndex(animatedZIndex)
                        .graphicsLayer {
                            translationX = animatedXOffset
                            scaleX = animatedScale
                            scaleY = animatedScale
                        },
            )
        }
    }
}

/**
 * Calculate the visual position of a cover in the stack.
 *
 * @param index The cover's index in the original list
 * @param frontIndex Which index is currently at the front
 * @param totalCount Total number of covers
 * @return Visual position (0 = back, totalCount-1 = front)
 */
private fun calculateVisualPosition(
    index: Int,
    frontIndex: Int,
    totalCount: Int,
): Int {
    // Position relative to front (0 = at front, wrapping around)
    val relativePosition = (index - frontIndex + totalCount) % totalCount
    // Invert so front = highest number for z-ordering
    return totalCount - 1 - relativePosition
}

/**
 * Individual cover in the stack with shadow and rounded corners.
 */
@Composable
private fun StackedCover(
    bookId: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier =
            modifier
                .shadow(
                    elevation = 4.dp,
                    shape = shape,
                    clip = false,
                ).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        BookCoverImage(
            bookId = bookId,
            coverPath = coverPath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape),
        )
    }
}
