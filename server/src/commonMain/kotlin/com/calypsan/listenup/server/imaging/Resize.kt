package com.calypsan.listenup.server.imaging

/**
 * Downscales to at most [maxWidth], preserving aspect ratio.
 *
 * **Area averaging, not sampling.** Every source pixel contributes to exactly one destination pixel,
 * weighted by nothing more than its presence. Nearest-neighbour would be cheaper and looks fine on
 * photographs, but a book cover is mostly *type* — dropping 15 of every 16 pixels shreds small text
 * into aliased noise, which is precisely the part of a cover a reader scans a grid for.
 *
 * Returns the receiver unchanged when it is already no wider than [maxWidth]: a cover is a
 * fixed-resolution asset, and enlarging one spends bytes inventing detail that was never captured.
 */
internal fun PixelBuffer.resizedTo(maxWidth: Int): PixelBuffer {
    require(maxWidth > 0) { "maxWidth must be positive, got $maxWidth" }
    if (width <= maxWidth) return this

    val targetWidth = maxWidth
    // Round to nearest rather than truncating, and never collapse to zero — an extreme aspect ratio
    // (a 1000x3 banner) would otherwise produce a zero-height buffer and fail construction.
    val targetHeight = ((height.toLong() * targetWidth + width / 2) / width).toInt().coerceAtLeast(1)

    val out = IntArray(targetWidth * targetHeight)

    for (destinationY in 0 until targetHeight) {
        // Source rows [rowStart, rowEnd) map onto this destination row. Computing the bounds from
        // the destination index — rather than walking the source and dividing — is what keeps the
        // trailing partial region: the last destination row's `rowEnd` is the source height itself.
        val rowStart = (destinationY.toLong() * height / targetHeight).toInt()
        val rowEnd =
            (((destinationY + 1).toLong() * height + targetHeight - 1) / targetHeight)
                .toInt()
                .coerceAtMost(height)
                .coerceAtLeast(rowStart + 1)

        for (destinationX in 0 until targetWidth) {
            val columnStart = (destinationX.toLong() * width / targetWidth).toInt()
            val columnEnd =
                (((destinationX + 1).toLong() * width + targetWidth - 1) / targetWidth)
                    .toInt()
                    .coerceAtMost(width)
                    .coerceAtLeast(columnStart + 1)

            var alphaSum = 0L
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            var count = 0

            for (sourceY in rowStart until rowEnd) {
                val rowOffset = sourceY * width
                for (sourceX in columnStart until columnEnd) {
                    val pixel = pixels[rowOffset + sourceX]
                    alphaSum += alpha(pixel)
                    redSum += red(pixel)
                    greenSum += green(pixel)
                    blueSum += blue(pixel)
                    count++
                }
            }

            // Rounded division, so a region of identical pixels reproduces that pixel exactly
            // rather than drifting a channel down by one.
            out[destinationY * targetWidth + destinationX] =
                packPixel(
                    alpha = ((alphaSum + count / 2) / count).toInt(),
                    red = ((redSum + count / 2) / count).toInt(),
                    green = ((greenSum + count / 2) / count).toInt(),
                    blue = ((blueSum + count / 2) / count).toInt(),
                )
        }
    }

    return PixelBuffer(targetWidth, targetHeight, out)
}
