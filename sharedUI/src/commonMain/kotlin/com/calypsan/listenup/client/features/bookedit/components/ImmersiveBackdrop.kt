package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.CoverColors
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage

/**
 * Immersive blurred background with gradient overlay.
 * Creates a premium editing experience with color-extracted theming.
 */
@Composable
fun ImmersiveBackdrop(
    coverPath: String?,
    refreshKey: Any?,
    coverColors: CoverColors,
    surfaceColor: Color,
    bookId: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred cover image
        if (bookId != null) {
            BookCoverImage(
                bookId = bookId,
                coverPath = coverPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .blur(50.dp),
            )
        } else if (coverPath != null) {
            ListenUpAsyncImage(
                path = coverPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                refreshKey = refreshKey,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .blur(50.dp),
            )
        }

        // Gradient overlay from cover color to surface
        val gradientColors =
            listOf(
                coverColors.darkMuted.copy(alpha = 0.85f),
                coverColors.darkMuted.copy(alpha = 0.6f),
                surfaceColor.copy(alpha = 0.95f),
                surfaceColor,
            )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(450.dp)
                    .background(Brush.verticalGradient(gradientColors)),
        )
    }
}
