package com.calypsan.listenup.client.data.local.images

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [CoverColorExtractor] using androidx.palette.
 *
 * Extracts a color palette from image bytes by:
 * 1. Decoding bytes to Bitmap
 * 2. Running Palette.generate() to analyze colors
 * 3. Extracting dominant, darkMuted, and vibrant swatches
 *
 * Runs on Dispatchers.Default as Palette analysis is CPU-intensive.
 */
class AndroidCoverColorExtractor : CoverColorExtractor {
    override suspend fun extractColors(imageBytes: ByteArray): ExtractedColors? =
        withContext(Dispatchers.Default) {
            try {
                // Decode image bytes to bitmap
                val bitmap =
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        ?: return@withContext null

                // Generate palette (synchronous, CPU-intensive)
                val palette = Palette.from(bitmap).generate()

                // Extract key colors, using dominant as fallback
                val dominantSwatch = palette.dominantSwatch
                val dominantColor = dominantSwatch?.rgb ?: return@withContext null

                val darkMutedColor =
                    palette.darkMutedSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: dominantColor

                val vibrantColor =
                    palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: dominantColor

                // Recycle bitmap to free memory
                bitmap.recycle()

                ExtractedColors(
                    dominant = dominantColor,
                    darkMuted = darkMutedColor,
                    vibrant = vibrantColor,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                null
            }
        }
}
