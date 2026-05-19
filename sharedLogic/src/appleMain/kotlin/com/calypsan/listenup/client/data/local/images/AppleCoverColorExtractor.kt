package com.calypsan.listenup.client.data.local.images

/**
 * iOS implementation of [CoverColorExtractor].
 *
 * Currently returns null - color extraction not yet implemented for iOS.
 * Future implementation could use Core Graphics/UIKit to extract dominant colors.
 *
 * The UI gracefully handles null colors by falling back to runtime extraction.
 */
class AppleCoverColorExtractor : CoverColorExtractor {
    override suspend fun extractColors(imageBytes: ByteArray): ExtractedColors? = null
}
