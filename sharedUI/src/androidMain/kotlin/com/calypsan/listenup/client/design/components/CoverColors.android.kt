@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation of rememberCoverColors using Palette API.
 *
 * Optimization strategy:
 * 1. If cached colors are provided (from database), use them immediately
 * 2. If no cached colors, fall back to runtime Palette extraction
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
    val context = LocalContext.current
    val defaultColors = defaultCoverColors(fallbackColor)

    // If we have cached colors, use them immediately - no extraction needed
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

    // No cached colors - fall back to runtime extraction

    // Build cache key using file modification time for automatic invalidation
    val cacheKey by produceState<String?>(
        initialValue = imagePath,
        key1 = imagePath,
        key2 = refreshKey,
    ) {
        value =
            withContext(Dispatchers.IO) {
                imagePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) "$path:${file.lastModified()}" else path
                }
            }
    }

    // Return default if no image path
    if (imagePath == null || cacheKey == null) {
        return defaultColors
    }

    var colorScheme by remember(cacheKey) { mutableStateOf(defaultColors) }

    // Load image with hardware acceleration disabled for Palette extraction
    val painter =
        rememberAsyncImagePainter(
            model =
                ImageRequest
                    .Builder(context)
                    .data(imagePath)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .allowHardware(false) // Required for Palette extraction
                    .build(),
        )

    LaunchedEffect(painter.state.value, cacheKey) {
        val state = painter.state.value
        if (state is AsyncImagePainter.State.Success) {
            val extracted =
                withContext(Dispatchers.Default) {
                    try {
                        val bitmap = state.result.image.toBitmap()
                        extractCoverColors(bitmap, fallbackColor)
                    } catch (
                        @Suppress("SwallowedException") e: Exception,
                    ) {
                        null
                    }
                }
            if (extracted != null) {
                colorScheme = extracted
            }
        }
    }

    return colorScheme
}

/**
 * Extracts a color scheme from a bitmap using the Palette API.
 */
private fun extractCoverColors(
    bitmap: Bitmap,
    fallbackColor: Color,
): CoverColors {
    val palette = Palette.from(bitmap).generate()

    val dominant = palette.dominantSwatch?.rgb?.let { Color(it) } ?: fallbackColor
    val vibrant = palette.vibrantSwatch?.rgb?.let { Color(it) } ?: dominant
    val darkVibrant = palette.darkVibrantSwatch?.rgb?.let { Color(it) } ?: dominant
    val lightVibrant = palette.lightVibrantSwatch?.rgb?.let { Color(it) } ?: dominant
    val muted = palette.mutedSwatch?.rgb?.let { Color(it) } ?: dominant
    val darkMuted = palette.darkMutedSwatch?.rgb?.let { Color(it) } ?: dominant

    val onDominant =
        palette.dominantSwatch?.let {
            Color(it.titleTextColor)
        } ?: Color.White

    return CoverColors(
        dominant = dominant,
        vibrant = vibrant,
        darkVibrant = darkVibrant,
        lightVibrant = lightVibrant,
        muted = muted,
        darkMuted = darkMuted,
        onDominant = onDominant,
    )
}
