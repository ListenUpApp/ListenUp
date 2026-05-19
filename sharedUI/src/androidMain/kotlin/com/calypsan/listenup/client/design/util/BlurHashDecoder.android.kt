package com.calypsan.listenup.client.design.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android BlurHash decoder using common core + Android Bitmap.
 */
actual fun decodeBlurHash(
    blurHash: String,
    width: Int,
    height: Int,
): ImageBitmap? {
    val pixels = BlurHashCore.decode(blurHash, width, height) ?: return null
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
