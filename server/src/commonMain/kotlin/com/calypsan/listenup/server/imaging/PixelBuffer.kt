package com.calypsan.listenup.server.imaging

/**
 * An uncompressed image: [width] × [height] pixels, row-major, one packed `RGBA` int each.
 *
 * The currency every stage of the pipeline trades in — decoders produce it, [resizedTo] transforms
 * it, encoders consume it. Deliberately dumb: no colour management, no metadata, no orientation.
 * A cover is pixels, and everything the pipeline needs to know about it is its size.
 *
 * Packing is `0xAARRGGBB`, matching the byte order the encoders and decoders in this package agree
 * on. Nothing outside this package should need to know that — read the channels through [red],
 * [green], [blue] and [alpha] rather than shifting by hand.
 */
class PixelBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "PixelBuffer must have positive dimensions, got ${width}x$height" }
        require(pixels.size == width * height) {
            "PixelBuffer expects ${width * height} pixels for ${width}x$height, got ${pixels.size}"
        }
    }
}

/** The alpha channel of a packed pixel, 0–255. */
internal fun alpha(pixel: Int): Int = (pixel ushr ALPHA_SHIFT) and CHANNEL_MASK

/** The red channel of a packed pixel, 0–255. */
internal fun red(pixel: Int): Int = (pixel ushr RED_SHIFT) and CHANNEL_MASK

/** The green channel of a packed pixel, 0–255. */
internal fun green(pixel: Int): Int = (pixel ushr GREEN_SHIFT) and CHANNEL_MASK

/** The blue channel of a packed pixel, 0–255. */
internal fun blue(pixel: Int): Int = pixel and CHANNEL_MASK

/** Packs four 0–255 channels into the `0xAARRGGBB` layout this package uses. */
internal fun packPixel(
    alpha: Int,
    red: Int,
    green: Int,
    blue: Int,
): Int = (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

private const val CHANNEL_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
