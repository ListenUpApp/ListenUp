@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Desktop implementation of rememberCoverColors.
 *
 * Uses cached colors from database when available, otherwise returns theme-based defaults.
 * Does not perform runtime color extraction (no Palette API equivalent on Desktop).
 */
@Composable
actual fun rememberCoverColors(
    imagePath: String?,
    cachedDominantColor: Int?,
    cachedDarkMutedColor: Int?,
    cachedVibrantColor: Int?,
    refreshKey: Any?,
    fallbackColor: Color,
): CoverColors {
    // If we have cached colors, use them
    if (cachedDominantColor != null && cachedDarkMutedColor != null) {
        val dominant = Color(cachedDominantColor)
        val darkMuted = Color(cachedDarkMutedColor)
        val vibrant = if (cachedVibrantColor != null) Color(cachedVibrantColor) else dominant

        return CoverColors(
            dominant = dominant,
            vibrant = vibrant,
            darkVibrant = darkMuted,
            lightVibrant = vibrant,
            muted = darkMuted,
            darkMuted = darkMuted,
            onDominant = if (isColorLight(cachedDominantColor)) Color.Black else Color.White,
        )
    }

    // No cached colors available - return defaults based on fallback color
    return defaultCoverColors(fallbackColor)
}
