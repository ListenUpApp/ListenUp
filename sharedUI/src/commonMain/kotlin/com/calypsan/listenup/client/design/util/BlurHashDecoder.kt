package com.calypsan.listenup.client.design.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a BlurHash string to an ImageBitmap placeholder.
 *
 * Platform implementations:
 * - Android: Uses com.vanniktech.blurhash library
 * - Desktop: Uses pure-JVM BlurHash decoding with AWT BufferedImage
 */
expect fun decodeBlurHash(
    blurHash: String,
    width: Int,
    height: Int,
): ImageBitmap?
