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
 * The glow is a blurred circle filled with [primaryContainer] at reduced opacity,
 * offset slightly upward to create a natural light-source impression.
 *
 * @param coverPath Local file path to the cover image, or null.
 * @param bookId Book identifier used for server-URL fallback image loading.
 * @param coverBlurHash Optional BlurHash placeholder string.
 * @param size Side length of the square cover (glow is proportionally larger).
 */
@Composable
fun PlayerArtwork(
    coverPath: String?,
    bookId: String,
    coverBlurHash: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val glowSize = size * 1.2f

    Box(
        modifier = modifier.size(glowSize),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient glow — blurred primaryContainer circle behind the cover.
        Box(
            modifier =
                Modifier
                    .size(glowSize)
                    .offset(y = -size * 0.05f)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    ).blur(40.dp),
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
