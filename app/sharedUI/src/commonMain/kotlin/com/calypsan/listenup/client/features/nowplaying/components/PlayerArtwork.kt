package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard

/**
 * Artwork component for the Now Playing screen.
 *
 * Renders the book cover via [ElevatedCoverCard] over a soft ambient glow. The glow is a radial
 * gradient — [primaryContainer] at the centre fading to fully transparent at the edge — so it reads
 * as diffuse light emanating from behind the cover rather than a defined shape. Its bright centre
 * sits behind the cover; only the gentle outer falloff is visible around the cover's edges.
 *
 * The component's layout footprint is the cover [size]; the larger glow is drawn as a centred
 * overlay that overflows those bounds without reserving extra space, so the artwork doesn't waste
 * vertical room on its (mostly transparent) glow margin.
 *
 * @param coverPath Local file path to the cover image, or null.
 * @param bookId Book identifier used for server-URL fallback image loading.
 * @param size Side length of the square cover, and the component's layout footprint.
 * @param title Book title, shown in the gradient fallback when no cover can be loaded.
 * @param author Primary author, shown in the gradient fallback when no cover can be loaded.
 */
@Composable
fun PlayerArtwork(
    coverPath: String?,
    bookId: String,
    size: Dp,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    coverHash: String? = null,
) {
    // The glow extends beyond the cover so the gradient has room to fade to nothing. It is an overlay
    // only — the Box footprint stays at [size], so the glow overflows without reserving layout space.
    val glowSize = size * 1.5f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient glow — a radial gradient fading to transparent reads as light, not a shape.
        Box(
            modifier =
                Modifier
                    .size(glowSize)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    Color.Transparent,
                                ),
                        ),
                    ),
        )

        ElevatedCoverCard(
            path = coverPath,
            bookId = bookId,
            coverHash = coverHash,
            contentDescription = null,
            title = title,
            author = author,
            cornerRadius = 20.dp,
            elevation = 24.dp,
            modifier = Modifier.size(size),
        )
    }
}
