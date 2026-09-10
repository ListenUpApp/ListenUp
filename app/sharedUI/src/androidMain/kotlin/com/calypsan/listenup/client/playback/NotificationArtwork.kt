package com.calypsan.listenup.client.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Longest edge, in pixels, a notification large-icon bitmap is allowed to reach.
 *
 * A media notification draws its large icon at a few tens of dp; library covers arrive at
 * provider-native resolution (routinely 1500–2400 px). At ARGB_8888 a 2400 px square is
 * ~23 MB, and the provider caches several — enough to push the process into the
 * REASON_LOW_MEMORY exits ListenUp.kt already reports. 512 px keeps a single bitmap
 * under ~1 MB while staying sharp on the largest surface that shows it.
 */
internal const val NOTIFICATION_ARTWORK_MAX_EDGE_PX = 512

/**
 * Power-of-two `BitmapFactory.Options.inSampleSize` that brings the longest edge of a
 * [srcWidth] x [srcHeight] image to at most [maxEdgePx].
 *
 * Returns 1 for degenerate input (a non-positive dimension or cap) so a failed bounds
 * decode cannot spin this loop.
 */
internal fun computeInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxEdgePx: Int,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxEdgePx <= 0) return 1
    var sample = 1
    while (maxOf(srcWidth, srcHeight) / sample > maxEdgePx) sample *= 2
    return sample
}

/**
 * Two-pass decode: read the bounds, pick an [computeInSampleSize] factor, then decode at
 * that factor. [openStream] is called twice because a stream cannot be rewound — pass a
 * factory, not a stream.
 */
internal fun decodeDownsampled(
    openStream: () -> InputStream?,
    maxEdgePx: Int = NOTIFICATION_ARTWORK_MAX_EDGE_PX,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}
