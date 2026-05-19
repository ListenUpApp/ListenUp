package com.calypsan.listenup.client.design.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

/**
 * Desktop BlurHash decoder using common core + AWT BufferedImage.
 */
actual fun decodeBlurHash(
    blurHash: String,
    width: Int,
    height: Int,
): ImageBitmap? {
    val pixels = BlurHashCore.decode(blurHash, width, height) ?: return null
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}
