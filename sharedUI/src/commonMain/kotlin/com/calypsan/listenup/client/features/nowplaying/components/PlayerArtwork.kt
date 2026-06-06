package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard

/**
 * Artwork component for the Now Playing screen.
 *
 * Renders the book cover via [ElevatedCoverCard] with a soft ambient glow behind it.
 * The glow is a wider-than-tall ellipse (wider than the cover, shorter than the cover) filled
 * with [primaryContainer] at reduced opacity, nudged slightly upward so the bulk of it hides
 * behind the cover and only a warm halo bleeds out the left/right edges.
 *
 * @param coverPath Local file path to the cover image, or null.
 * @param bookId Book identifier used for server-URL fallback image loading.
 * @param coverBlurHash Optional BlurHash placeholder string.
 * @param size Side length of the square cover (glow is proportionally sized).
 */
@Composable
fun PlayerArtwork(
    coverPath: String?,
    bookId: String,
    coverBlurHash: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Wider than the cover, shorter than the cover — bulk hides behind; halo bleeds left/right.
    val glowWidth = size * 1.18f
    val glowHeight = size * 0.82f
    // Outer container matches the cover size so nothing clips the cover itself.
    val containerSize = size * 1.18f

    Box(
        modifier = modifier.size(containerSize),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient glow — blurred primaryContainer ellipse behind the cover.
        // CircleShape on a non-square Box produces a stadium/ellipse; blur softens it to a halo.
        Box(
            modifier =
                Modifier
                    .size(width = glowWidth, height = glowHeight)
                    .offset(y = -(size * 0.03f))
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    ).blur(32.dp),
        )

        ElevatedCoverCard(
            path = coverPath,
            bookId = bookId,
            blurHash = coverBlurHash,
            contentDescription = null,
            cornerRadius = 20.dp,
            elevation = 24.dp,
            modifier = Modifier.size(size),
        )
    }
}
