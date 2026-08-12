package com.calypsan.listenup.server.imaging

/**
 * Turns decoded coefficients into pixels at a reduced scale.
 *
 * [reduction] is 8 (one pixel per block, from DC alone) or 4 (2x2 pixels per block, from the
 * top-left 2x2 coefficients). Both are exact reductions of the full inverse DCT restricted to the
 * coefficients we kept — not a full reconstruction followed by a resize, which is the entire point.
 */
internal fun reconstruct(
    segments: JpegSegments,
    reduction: Int,
): PixelBuffer? {
    val frame = segments.frame
    val pixelsPerBlock = DCT_SIZE / reduction
    val width = ceilDiv(frame.width, reduction)
    val height = ceilDiv(frame.height, reduction)
    if (width <= 0 || height <= 0) return null

    val planes =
        frame.components.map { component ->
            val quant = segments.quantTables[component.quantTable] ?: return null
            renderPlane(component, quant, pixelsPerBlock)
        }

    val out = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            out[y * width + x] =
                if (frame.components.size == 1) {
                    val grey = planes[0].sampleFor(frame, frame.components[0], x, y, width, height)
                    packPixel(OPAQUE, grey, grey, grey)
                } else {
                    val luma = planes[0].sampleFor(frame, frame.components[0], x, y, width, height)
                    val blueDiff = planes[1].sampleFor(frame, frame.components[1], x, y, width, height)
                    val redDiff = planes[2].sampleFor(frame, frame.components[2], x, y, width, height)
                    yCbCrToRgb(luma, blueDiff, redDiff)
                }
        }
    }
    return PixelBuffer(width, height, out)
}

/** One component's samples at the reduced scale. */
private class Plane(
    val width: Int,
    val height: Int,
    val samples: IntArray,
)

/**
 * Samples a component plane for an output pixel, accounting for chroma subsampling.
 *
 * A 4:2:0 image carries half-resolution chroma, so the plane is smaller than the output and is
 * stretched by nearest sample. At these scales a plane is a handful of pixels across and anything
 * more elaborate would be invisible.
 */
private fun Plane.sampleFor(
    frame: JpegFrame,
    component: JpegComponent,
    x: Int,
    y: Int,
    outWidth: Int,
    outHeight: Int,
): Int {
    val planeX =
        if (component.horizontalSampling == frame.maxHorizontalSampling) {
            x
        } else {
            x * component.horizontalSampling / frame.maxHorizontalSampling
        }
    val planeY =
        if (component.verticalSampling == frame.maxVerticalSampling) {
            y
        } else {
            y * component.verticalSampling / frame.maxVerticalSampling
        }
    val clampedX = planeX.coerceIn(0, width - 1).coerceAtMost(outWidth)
    val clampedY = planeY.coerceIn(0, height - 1).coerceAtMost(outHeight)
    return samples[clampedY * width + clampedX]
}

private fun renderPlane(
    component: JpegComponent,
    quant: IntArray,
    pixelsPerBlock: Int,
): Plane {
    val width = component.blocksPerLine * pixelsPerBlock
    val height = component.blocksPerColumn * pixelsPerBlock
    val samples = IntArray(width * height)

    for (blockRow in 0 until component.blocksPerColumn) {
        for (blockColumn in 0 until component.blocksPerLine) {
            val base = (blockRow * component.blocksPerLine + blockColumn) * BLOCK_COEFFICIENTS
            val dc = component.coefficients[base] * quant[0]

            if (pixelsPerBlock == 1) {
                // DC alone is the block's mean: divide out the DCT's 8x scaling and re-centre.
                val value = (dc / DCT_SIZE) + LEVEL_SHIFT
                samples[blockRow * width + blockColumn] = value.coerceIn(0, MAX_CHANNEL)
            } else {
                // 2x2 from the top-left 2x2 coefficients — the separable inverse DCT truncated to
                // its two lowest basis functions in each direction.
                val c01 = component.coefficients[base + 1] * quant[1]
                val c10 = component.coefficients[base + DCT_SIZE] * quant[DCT_SIZE]
                val c11 = component.coefficients[base + DCT_SIZE + 1] * quant[DCT_SIZE + 1]
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val horizontal = if (dx == 0) 1 else -1
                        val vertical = if (dy == 0) 1 else -1
                        val sum =
                            dc +
                                horizontal * scaleOdd(c01) +
                                vertical * scaleOdd(c10) +
                                horizontal * vertical * scaleOdd(c11)
                        val value = (sum / DCT_SIZE) + LEVEL_SHIFT
                        val px = blockColumn * 2 + dx
                        val py = blockRow * 2 + dy
                        samples[py * width + px] = value.coerceIn(0, MAX_CHANNEL)
                    }
                }
            }
        }
    }
    return Plane(width, height, samples)
}

/**
 * Weight of the first odd basis function, averaged over half a block.
 *
 * cos(pi/16)-family terms average to roughly 0.9 of the coefficient across each half; 231/256
 * carries that in integer arithmetic without a floating-point multiply per sample.
 */
private fun scaleOdd(coefficient: Int): Int = (coefficient * ODD_WEIGHT_NUMERATOR) / ODD_WEIGHT_DENOMINATOR

private fun yCbCrToRgb(
    luma: Int,
    blueDiff: Int,
    redDiff: Int,
): Int {
    val cb = blueDiff - LEVEL_SHIFT
    val cr = redDiff - LEVEL_SHIFT
    val red = (luma + (RED_CR * cr) / FIXED_POINT).coerceIn(0, MAX_CHANNEL)
    val green = (luma - (GREEN_CB * cb + GREEN_CR * cr) / FIXED_POINT).coerceIn(0, MAX_CHANNEL)
    val blue = (luma + (BLUE_CB * cb) / FIXED_POINT).coerceIn(0, MAX_CHANNEL)
    return packPixel(OPAQUE, red, green, blue)
}

private const val LEVEL_SHIFT = 128
private const val MAX_CHANNEL = 255
private const val OPAQUE = 255
private const val ODD_WEIGHT_NUMERATOR = 231
private const val ODD_WEIGHT_DENOMINATOR = 256
private const val FIXED_POINT = 1024
private const val RED_CR = 1436
private const val GREEN_CB = 352
private const val GREEN_CR = 731
private const val BLUE_CB = 1815
