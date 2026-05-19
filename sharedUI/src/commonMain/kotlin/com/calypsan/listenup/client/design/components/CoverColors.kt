@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Color scheme extracted from cover art.
 * Provides a comprehensive set of colors for dynamic theming based on cover images.
 */
data class CoverColors(
    /** The most dominant color in the image */
    val dominant: Color,
    /** A vibrant color extracted from the image */
    val vibrant: Color,
    /** A dark vibrant color for backgrounds */
    val darkVibrant: Color,
    /** A light vibrant color for accents */
    val lightVibrant: Color,
    /** A muted color for subtle backgrounds */
    val muted: Color,
    /** A dark muted color for darker backgrounds */
    val darkMuted: Color,
    /** Contrasting text color for use on dominant background */
    val onDominant: Color,
)

/**
 * Creates a default CoverColors instance with a single color.
 * Useful for previews and fallback states.
 */
fun defaultCoverColors(color: Color = Color(0xFF424242)): CoverColors =
    CoverColors(
        dominant = color,
        vibrant = color,
        darkVibrant = color,
        lightVibrant = color,
        muted = color,
        darkMuted = color,
        onDominant = Color.White,
    )

/**
 * Determines if a color is light (for choosing contrasting text color).
 */
fun isColorLight(color: Int): Boolean {
    val red = (color shr 16) and 0xFF
    val green = (color shr 8) and 0xFF
    val blue = color and 0xFF
    val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
    return luminance > 0.5
}

/**
 * Composable that provides cover colors, using cached values when available.
 *
 * Platform-specific implementations:
 * - Android: Uses Palette API for runtime color extraction from bitmaps
 * - Desktop: Uses cached colors only, falling back to theme defaults
 *
 * @param imagePath Local file path to the cover image
 * @param cachedDominantColor Cached dominant color as ARGB int (from database)
 * @param cachedDarkMutedColor Cached dark muted color for gradients
 * @param cachedVibrantColor Cached vibrant accent color
 * @param refreshKey Optional key to force re-extraction
 * @param fallbackColor Color to use when extraction fails
 * @return Extracted color scheme, or default scheme based on fallbackColor
 */
@Composable
expect fun rememberCoverColors(
    imagePath: String?,
    cachedDominantColor: Int? = null,
    cachedDarkMutedColor: Int? = null,
    cachedVibrantColor: Int? = null,
    refreshKey: Any? = null,
    fallbackColor: Color = MaterialTheme.colorScheme.primaryContainer,
): CoverColors
