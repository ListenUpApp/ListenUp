package com.calypsan.listenup.client.data.local.images

/**
 * Extracted color palette from a cover image.
 *
 * Colors are stored as ARGB integers for efficient storage and
 * direct use with Compose Color(int) constructor.
 *
 * @property dominant The most prominent color in the image
 * @property darkMuted A dark, muted variant suitable for gradients/backgrounds
 * @property vibrant A vibrant accent color
 */
data class ExtractedColors(
    val dominant: Int,
    val darkMuted: Int,
    val vibrant: Int,
)

/**
 * Platform-specific interface for extracting color palettes from images.
 *
 * Android: Uses androidx.palette to analyze bitmap colors
 * iOS: Currently returns null (color extraction not yet implemented)
 *
 * Used during image download to cache colors in the database,
 * enabling instant gradient rendering without runtime extraction.
 */
interface CoverColorExtractor {
    /**
     * Extract color palette from raw image bytes.
     *
     * @param imageBytes Raw image data (JPEG/PNG)
     * @return Extracted colors, or null if extraction fails or is unsupported
     */
    suspend fun extractColors(imageBytes: ByteArray): ExtractedColors?
}
